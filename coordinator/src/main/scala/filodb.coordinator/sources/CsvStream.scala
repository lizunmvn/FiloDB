package filodb.coordinator.sources

import com.opencsv.CSVReader
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import monix.reactive.Observable
import net.ceedubs.ficus.Ficus._
import filodb.memory.format.ArrayStringRowReader

import filodb.coordinator.{IngestionStream, IngestionStreamFactory}
import filodb.core.memstore.{IngestRecord, IngestRouting}
import filodb.core.metadata.Dataset

object CsvStream extends StrictLogging {
  // Number of lines to read and send at a time
  val BatchSize = 100

  final case class CsvStreamSettings(header: Boolean = true,
                                     batchSize: Int = BatchSize,
                                     separatorChar: Char = ',')

  def getHeaderColumns(csvStream: java.io.Reader,
                       separatorChar: Char = ','): (Seq[String], CSVReader) = {
    val reader = new CSVReader(csvStream, separatorChar)
    (reader.readNext.toSeq, reader)
  }

  def getHeaderColumns(csvPath: String): (Seq[String], CSVReader) = {
    val fileReader = new java.io.FileReader(csvPath)
    getHeaderColumns(fileReader)
  }
}

/**
 * Config for CSV ingestion:
 * {{{
 *   file = "/path/to/file.csv"
 *   header = true
 *   batch-size = 100
 *   # separator-char = ","
 *   column-names = ["time", "value"]
 * }}}
 * Instead of file one can put "resource"
 *
 * If the CSV has a header, you need to set header=true.
 * Then the header will be parsed automatically.
 * Otherwise you must pass in the column names in the config.
 *
 * Offsets created are the line numbers, where the first line after the header is offset 0, next one 1, etc.
 * This Stream Factory is capable of rewinding to a given line number when given a positive offset.
 *
 * NOTE: right now this only works with a single shard.
 */
class CsvStreamFactory extends IngestionStreamFactory {
  import CsvStream._

  def create(config: Config, dataset: Dataset, shard: Int, offset: Option[Long]): IngestionStream = {
    require(shard == 0, s"Shard on creation must be shard 0 but was '$shard'.")
    val settings = CsvStreamSettings(config.getBoolean("header"),
                     config.as[Option[Int]]("batch-size").getOrElse(BatchSize),
                     config.as[Option[String]]("separator-char").getOrElse(",").charAt(0))
    val reader = config.as[Option[String]]("file").map { filePath =>
                   new java.io.FileReader(filePath)
                 }.getOrElse {
                   new java.io.InputStreamReader(getClass.getResourceAsStream(config.getString("resource")))
                 }

    val (columnNames, csvReader) = if (settings.header) {
      getHeaderColumns(reader, settings.separatorChar)
    } else {
      (config.as[Seq[String]]("column-names"), new CSVReader(reader, settings.separatorChar))
    }
    new CsvStream(csvReader, dataset, columnNames, settings, offset)
  }
}

/**
 * CSV post-header reader.
 * Either the CSV file has no headers, in which case the column names must be supplied,
 * or you can read the first line and parse the headers and then invoke this class.
 *
 * @param offset the number of lines to skip; must be >=0 and <= Int.MaxValue or will reset to 0
 */
private[filodb] class CsvStream(csvReader: CSVReader,
                                dataset: Dataset,
                                columnNames: Seq[String],
                                settings: CsvStream.CsvStreamSettings,
                                offset: Option[Long]) extends IngestionStream with StrictLogging {
  import collection.JavaConverters._

  val numLinesToSkip = offset.filter(n => n >= 0 && n <= Int.MaxValue).map(_.toInt)
                             .getOrElse {
                               logger.info(s"Possibly resetting offset to 0; supplied offset $offset")
                               0
                             }

  val routing = IngestRouting(dataset, columnNames)
  logger.info(s"CsvStream started with dataset ${dataset.ref}, columnNames $columnNames, routing $routing")
  if (numLinesToSkip > 0) logger.info(s"Skipping initial $numLinesToSkip lines...")

  val batchIterator = csvReader.iterator.asScala
                        .zipWithIndex
                        .drop(numLinesToSkip)
                        .map { case (tokens, idx) =>
                          IngestRecord(routing, ArrayStringRowReader(tokens), idx)
                        }.grouped(settings.batchSize)
  val get = Observable.fromIterator(batchIterator)

  def teardown(): Unit = {
    csvReader.close()
  }
}