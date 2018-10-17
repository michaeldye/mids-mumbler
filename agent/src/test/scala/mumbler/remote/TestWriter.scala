import collection.mutable.Stack
import org.scalatest._
import java.net.URI
import java.nio.file.Paths

import scala.io.Source

import mumbler.remote.Writer
import mumbler.remote.Recorder

class TestWriterSpec extends FlatSpec {

  // TODO: do cleanup after tests run
	def fixture = new {
		val temp = Paths.get(System.getProperty("java.io.tmpdir"), s"${System.currentTimeMillis()}")
		temp.toFile().mkdirs()

		val outDir = new URI(s"file://${temp.toString()}")
		// val list = new URI(s"file://${Source.fromURL(getClass().getResource("/list.fake"))}")
		// val pw = new PrintWriter(new File(list))
		// assert (Paths.get(".").toAbsolutePath().toString() == null )
		val records = Paths.get("mumbler","src","test","resources","3gram-9-sub").toUri()
		//assert (list.getRawPath() == null)
		val gramzip = new URI("http://localhost:9008/googlebooks-eng-us-all-2gram-20090715-60.csv.zip")
	}

	"indicesOf" should "return indices of all tabs in string" in {
		val has4 = "n1 n2 n3 td\tz\t4\t4"

		assert(Writer.indicesOf(has4, '\t') == List(11, 13, 15))
	}

  "recorder" should "cache lines with the same substring content" in {
    val l1 = "fooo\tgoo"
    val l2 = "fooo\tzoo"
    val l3 = "BOOO\tnoo"
    val l4 = "VOOOO\ttoo"

    val recorder = new Recorder()
    assert(recorder.record(l1, 4) == None)
    assert(recorder.record(l2, 4) == None)
    assert(recorder.record(l3, 4) == Some(List((l2, 4),(l1, 4))))

    val recorder2 = new Recorder(recorder.prev)
    // we're recording l4 but what is emitted should be l3 w/ its split index
    assert(recorder2.record(l4, 5) == Some(List((l3, 4))))
  }

	//"collect function" should "split primary records into cache jobs" in {
	//	val f = fixture
	//	val foo = Writer.collect(f.temp, f.gramzip)
  //}
}
