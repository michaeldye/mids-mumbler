import collection.mutable.Stack
import org.scalatest._
import java.net.URI
import java.nio.file.Paths

import scala.io.Source

import mumbler.remote.Writer
import mumbler.remote.Recorder

class ExampleSpec extends FlatSpec {

	def fixture = new {
		val temp = Paths.get(System.getProperty("java.io.tmpdir"), s"${System.currentTimeMillis()}")
		temp.toFile().mkdirs()

		val outDir = new URI(s"file://${temp.toString()}")
		// val list = new URI(s"file://${Source.fromURL(getClass().getResource("/list.fake"))}")
		// val pw = new PrintWriter(new File(list))
		// assert (Paths.get(".").toAbsolutePath().toString() == null )
		val records = Paths.get("mumbler","src","test","resources","3gram-9-sub").toUri()
		//assert (list.getRawPath() == null)
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
    assert(recorder.record(l3, 4) == Some(List(l2,l1)))

    val recorder2 = new Recorder(recorder.prev)
    assert(recorder2.record(l4, 5) == Some(List(l3)))
  }

	"collect function" should "split primary records into cache jobs" in {
		val f = fixture
		val foo = Writer.collect(f.temp, f.records)
  }
}
