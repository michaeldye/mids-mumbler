package mumbler.remote

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

import org.apache.http.client.fluent.Request

/**
 * @author mdye
 */
object Writer {
  def writeStats(dir: Path, word: String, follow: Map[String, Int]): Unit = {
    val out: File = Paths.get(dir.toString, word).toFile
    out.getParentFile.mkdirs
    
    val writer = new PrintWriter(out)

    follow.foreach { entry =>
      writer.println(s"${entry._1} ${entry._2}")
    }

    writer.close()
  }

  def preprocess(dir: Path, uri: URI): Boolean = {
    val to_dl = new File(dir.toFile, uri.getPath.split('/').last.split('.').head)
    
    if (to_dl.exists) false
    else {

      var word = ""
      var follow = Map[String, Int]()

      val zin = new ZipInputStream(Request.Get(uri).execute.returnContent.asStream)
      Stream.continually(zin.getNextEntry).takeWhile(_ != null).foreach(entry => {
        val entryBuffer = new BufferedReader(new InputStreamReader(zin, "UTF-8"))
        Stream.continually(entryBuffer.readLine).takeWhile(_ != null).
          map { _.split("\\s") }.
          foreach(words => {
            // process only if both first two words match pattern
            if (words.slice(0, 2).forall { Pattern.matches("^[A-Za-z][A-Za-z0-9]*", _) }) {

              if (words(0) != word) {
                // new first word

                if (word != "")
                  writeStats(dir, word, follow)

                word = words(0)
                follow = Map[String, Int]()
              } else {
                val add = words(2).toInt + 1
                val ct = follow get words(1) match {
                  case Some(num) => num + add
                  case None => add
                }
                follow += (words(1) -> ct)
              }

            }
          })
      })
     
      // touch the downloaded file name to know we got it
      to_dl.createNewFile
      writeStats(dir, word, follow)
      true
    }
  }
}