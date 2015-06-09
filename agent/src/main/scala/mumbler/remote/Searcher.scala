package mumbler.remote

import java.nio.file.Path
import java.io.File
import java.io.FileReader
import java.io.BufferedReader

/**
 * @author mdye
 */
object Searcher {
 
  def findFollowing(dir: Path, word: String): Option[Map[String, Int]] = {
    val wordFile = new File(dir.toFile, word)
    
    if (wordFile.canRead) {
      val reader = new BufferedReader(new FileReader(wordFile))
      
      Some(Stream.continually(reader.readLine).takeWhile { _ != null }.foldLeft(Map[String, Int]()){ (map, line) =>
        line.split(" ") match {
          case Array(word: String, count: String) => {
            map.updated(word, count.toInt)
          }
        }
      })
    } else None
  }
}