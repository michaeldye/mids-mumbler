package mumbler.remote

import com.typesafe.scalalogging.StrictLogging
import java.nio.file.Path
import java.io.File
import java.io.FileReader
import java.io.BufferedReader

/**
 * @author mdye
 */
class Searcher(val badwords: Seq[String]) extends StrictLogging {

  def findFollowing(dir: Path, word: String): Option[Map[String, Int]] = {
    val wordFile = new File(dir.toFile, word)

    if (wordFile.canRead) {
      val reader = new BufferedReader(new FileReader(wordFile))

      Some(Stream.continually(reader.readLine).takeWhile { _ != null }.foldLeft(Map[String, Int]()){ (map, line) =>
        line.split(" ") match {
          case Array(word: String, count: String) => {
            if ((!badwords.isEmpty) && badwords.contains(word.toLowerCase())) {
              logger.debug(s"Excluding word because it appears in badwords list: ${word}, occurrences: ${count}")
              map
            } else map.updated(word, count.toInt)
          }
          case _ => map
        }
      })
    } else None
  }
}
