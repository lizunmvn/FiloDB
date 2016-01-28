package filodb.core.metadata

import org.velvia.filo.RowReader
import scala.util.{Try, Success, Failure}

import filodb.core.{CompositeKeyType, KeyType, KeyRange, BinaryKeyRange}
import filodb.core.Types._

/**
 * A Projection defines one particular view of a dataset, designed to be optimized for a particular query.
 * It usually defines a sort order and subset of the columns.
 * Within a partition, **row key columns** define a unique primary key for each row.
 * Records/rows are grouped by the **segment key** into segments.
 * Projections are sorted by the **segment key**.
 *
 * By convention, projection 0 is the SuperProjection which consists of all columns from the dataset.
 *
 * The Projection base class is normalized, ie it doesn't have all the information.
 */
case class Projection(id: Int,
                      dataset: TableName,
                      keyColIds: Seq[ColumnId],
                      segmentColId: ColumnId,
                      reverse: Boolean = false,
                      // Nil columns means all columns
                      // Must include the rowKeyColumns and segmentColumn.
                      columns: Seq[ColumnId] = Nil)

/**
 * This is a Projection with information filled out from Dataset and Columns.
 * ie it has the actual Dataset and Column types as opposed to IDs, and a list of all columns.
 * It is also guaranteed to have a valid segmentColumn and dataset partition columns.
 */
case class RichProjection(projection: Projection,
                          dataset: Dataset,
                          columns: Seq[Column],
                          segmentColumn: Column,
                          segmentColIndex: Int,
                          segmentType: KeyType,
                          rowKeyColumns: Seq[Column],
                          rowKeyColIndices: Seq[Int],
                          rowKeyType: KeyType,
                          partitionColumns: Seq[Column],
                          partitionColIndices: Seq[Int],
                          partitionType: KeyType) {
  type SK = segmentType.T
  type RK = rowKeyType.T
  type PK = partitionType.T

  def datasetName: String = projection.dataset

  def segmentKeyFunc: RowReader => SK =
    segmentType.getKeyFunc(Array(segmentColIndex))

  def rowKeyFunc: RowReader => RK =
    rowKeyType.getKeyFunc(rowKeyColIndices.toArray)

  def partitionKeyFunc: RowReader => PK =
    partitionType.getKeyFunc(partitionColIndices.toArray)

  def toBinaryKeyRange[PK, SK](keyRange: KeyRange[PK, SK]): BinaryKeyRange =
    BinaryKeyRange(partitionType.toBytes(keyRange.partition.asInstanceOf[partitionType.T]),
                   segmentType.toBytes(keyRange.start.asInstanceOf[segmentType.T]),
                   segmentType.toBytes(keyRange.end.asInstanceOf[segmentType.T]),
                   keyRange.endExclusive)

  /**
   * Serializes this RichProjection into the minimal string needed to recover a "read-only" RichProjection,
   * ie the minimal RichProjection needed for segment reads and scans.  This means:
   * - Row key columns are not preserved
   * - Any ComputedColumns in partition / segment keys are converted to a DataColumn with same type
   * - Any ComputedKeyType is converted to a regular KeyType of the same type
   * - Only readColumns (+ partition and segment columns) are left in columns
   * - Most Projection and Dataset info is discarded
   */
  def toReadOnlyProjString(readColumns: Seq[String]): String = {
    val partitionColStrings = partitionColumns.zipWithIndex.map {
      case (ComputedColumn(id, _, _, colType, _), i) => DataColumn(id, s"part_$i", "", 0, colType).toString
      case (d: Column, i)                            => d.toString
    }
    val segmentColString = segmentColumn match {
      case ComputedColumn(id, _, _, colType, _) => DataColumn(id, "segCol", "", 0, colType).toString
      case d: Column                            => d.toString
    }
    val extraColStrings = columns.filter { col => readColumns.contains(col.name) }.map(_.toString)
    Seq(datasetName,
        projection.reverse.toString,
        partitionColStrings.mkString(":"),
        segmentColString,
        extraColStrings.mkString(":")).mkString("\001")
  }
}

object RichProjection {
  case class BadSchema(reason: String) extends Exception("BadSchema: " + reason)

  def apply(dataset: Dataset, columns: Seq[Column], projectionId: Int = 0): RichProjection =
    make(dataset, columns, projectionId).get

  // Returns computed (generated) columns to add to the schema if needed
  // TODO(velvia): try using Scalaz's ValidationNEL etc. could make code simpler
  private def getComputedColumns(datasetName: String,
                                 columnNames: Seq[String],
                                 origColumns: Seq[Column]): Try[Seq[Column]] = {
    val columns = columnNames.filter(ComputedColumn.isComputedColumn)
                             .map { expr =>
                               ComputedColumn.analyze(expr, datasetName, origColumns)
                                             .recover { case t: Throwable => return Failure(t) }.get
                             }
    Success(columns)
  }

  /**
   * Creates a full RichProjection, validating all the row, partition, and segment keys, and
   * creating proper KeyTypes for each type of key, including handling composite keys and
   * computed functions.
   * @return a Success(RichProjection), or Failure with the error, most likely a BadSchema.
   */
  def make(dataset: Dataset, columns: Seq[Column], projectionId: Int = 0): Try[RichProjection] = {
    def fail(reason: String): Try[RichProjection] = Failure(BadSchema(reason))

    if (projectionId >= dataset.projections.length) return fail(s"projectionId $projectionId missing")

    val normProjection = dataset.projections(projectionId)
    if (dataset.partitionColumns.isEmpty) return fail("Dataset partition columns cannot be empty")
    if (normProjection.keyColIds.isEmpty) return fail("Key columns cannot be empty")

    // NOTE: right now computed columns MUST be at the end, because Filo vectorization can't handle mixing
    val allColIds = dataset.partitionColumns ++ normProjection.keyColIds ++ Seq(normProjection.segmentColId)
    val tryComputedColumns = getComputedColumns(dataset.name, allColIds, columns)
    val allColumns = columns ++ tryComputedColumns.recover { case t: Throwable => return Failure(t) }.get

    val richColumns = {
      if (normProjection.columns.isEmpty) {
        allColumns
      } else {
        val columnMap = allColumns.map { c => c.name -> c }.toMap
        val missing = normProjection.columns.toSet -- columnMap.keySet
        if (missing.nonEmpty) return fail(s"Specified projection columns are missing: $missing")
        normProjection.columns.map(columnMap)
      }
    }

    val idToIndex = richColumns.zipWithIndex.map { case (col, i) => col.name -> i }.toMap

    val segmentColIndex = idToIndex.getOrElse(normProjection.segmentColId,
      return fail(s"Segment column ${normProjection.segmentColId} not in columns $richColumns"))
    val segmentColumn = richColumns(segmentColIndex)
    val segmentType = Column.columnsToKeyType(Seq(segmentColumn))
    if (!segmentType.isSegmentType) return fail(s"${segmentColumn.columnType} is not supported for segments")

    val rowKeyColIndices = normProjection.keyColIds.map { colId =>
      idToIndex.getOrElse(colId, return fail(s"Key column $colId not in columns $richColumns"))
    }
    val rowKeyColumns = rowKeyColIndices.map(richColumns)
    val rowKeyType = Column.columnsToKeyType(rowKeyColumns)

    val partitionColIndices = dataset.partitionColumns.map { colId =>
      idToIndex.getOrElse(colId, return fail(s"Partition column $colId not in columns $richColumns"))
    }
    val partitionColumns = partitionColIndices.map(richColumns)
    val partitionType = Column.columnsToKeyType(partitionColumns)

    Success(RichProjection(normProjection, dataset, richColumns,
                           segmentColumn, segmentColIndex, segmentType,
                           rowKeyColumns, rowKeyColIndices, rowKeyType,
                           partitionColumns, partitionColIndices, partitionType))
  }

  /**
   * Recovers a "read-only" RichProjection generated by toReadOnlyProjString().
   */
  def readOnlyFromString(serialized: String): RichProjection = {
    val Array(dsName, revStr, partColStr, segStr, extraColStr) = serialized.split('\001')
    val partitionColumns = partColStr.split(':').toSeq.map(raw => DataColumn.fromString(raw, dsName))
    val extraColumns = extraColStr.split(':').toSeq.map(raw => DataColumn.fromString(raw, dsName))
    val segmentColumn = DataColumn.fromString(segStr, dsName)
    val dataset = Dataset(dsName, Nil, segmentColumn.name, partitionColumns.map(_.name))

    RichProjection(dataset.projections.head, dataset, extraColumns,
                   segmentColumn, -1, Column.columnsToKeyType(Seq(segmentColumn)),
                   Nil, Nil, CompositeKeyType(Nil),
                   partitionColumns, Nil, Column.columnsToKeyType(partitionColumns))
  }
}