package mumbler.remote;

import java.util.List;
import java.util.LinkedList;
import java.util.AbstractQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.*;
import java.util.Arrays;
import java.net.URI;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// must be a singleton, not threadsafe
public class PreProcessor implements Runnable {

	// important that the buffer size on the stream itself is *larger* than our buffer size
	private final PipedInputStream inStr;

  private final AbstractQueue<String> reductionQueue;

	private final long cLength;

	private long totalProcessed = 0L;

  private final URI uri;

  private static final Logger logger = LoggerFactory.getLogger(PreProcessor.class);

  private static final int BUFFER_SIZE = 1024*1024*40;

  // shared buffer, dangerous but fast
  private static final byte[] BUFFER = new byte[BUFFER_SIZE];

	public PreProcessor(PipedOutputStream outStr, URI uri, Long bufSize, Long cLength, AbstractQueue<String> reductionQueue) throws IOException {
		inStr = new PipedInputStream(outStr, 800*1024*1024);
    this.cLength = cLength;
    this.uri = uri;
    this.reductionQueue = reductionQueue;
	}

  // reads from PipedInputStream until exhausted, splitting on 0x0a boundaries
	@Override
	public void run() {
    logger.info("Starting processing {}", uri.toString());

    try {
      // do the split in preprocessing, send off to thread for reduction
			int gramEndIx = -1;
			int ctBegIx = -1;
			int ctEndIx = -1;

			int prevIx;
      int ix;
			boolean con_illegal = false;

      // N.B. we're sloppy here and dropping lines that span buffers
      while (totalProcessed < cLength) {

        // consume it so we don't throttle the output pipe connected to the http client's entity stream
        int read = inStr.read(BUFFER, 0, BUFFER_SIZE);

        try {
          for  (ix = 0, prevIx = 0; ix < read; ix++) {

				  	// TODO: do a bit shift for comparison of a range
				  	if (BUFFER[ix] == 0x5f || BUFFER[ix] == 0x56 || BUFFER[ix] == 0x40 || BUFFER[ix] == 0x41) {
				  		con_illegal = true;
				  	} else if(BUFFER[ix] == 0x09) {
				  		if (gramEndIx == -1) {
				  			gramEndIx = ix;
				  		} else if (ctBegIx == -1) {
				  			ctBegIx = gramEndIx + 6; // we know that the date is always 4 digits
				  		} else if (ctEndIx == -1) {
				  			ctEndIx = ix;
				  		}
				  	}
            if (BUFFER[ix] == 0x0a) {
              if (ix != 0 && !con_illegal) {
				  			logger.debug("ix: {}, prevIx: {}, gramEndIx: {}, ctBegIx: {}, ctEndIx: {}, gram: {}, ct: {}", ix, prevIx, gramEndIx, ctBegIx, ctEndIx, new String(Arrays.copyOfRange(BUFFER, prevIx, gramEndIx)), new String(Arrays.copyOfRange(BUFFER, ctBegIx, ctEndIx)));

				  			reductionQueue.add(new String(Arrays.copyOfRange(BUFFER, prevIx, gramEndIx)));
              } else {
				  			con_illegal = false;
				  		}

            	prevIx = ix;

				  		gramEndIx = -1;
				  		ctBegIx = -1;
				  		ctEndIx = -1;
            }
          }
        } catch (Exception ex) {
          logger.debug("Exception while processing input pipe data", ex);
        }

        totalProcessed += read;

        if (totalProcessed % (1024*1024*512) == 0) {
          logger.info("Preprocessed {} bytes so far of {} for {}", totalProcessed, cLength, uri.toString());
        }
      }

    } catch (Exception ex) {
      logger.error("Failed to read input pipe", ex);
    } finally {
      try {
        inStr.close();
      } catch (Exception ex) {
        logger.error("Failed to close pipe", ex);
      }
    }

    logger.info("Finished with {}", uri.toString());

  	// Array[Int] buffer = new Array[Int](bufSize);

    // val procBuffer = ByteBuffer.allocateDirect(800*1024*1024)

		//logger.info(s"Processing thread reading lines from ${uri.toString}")

//
//		while (processed < cLength) {
//			// N.B. can't use buffer.size, not sure why
//			val read = inStr.read(buffer, 0, bufSize)
//
//      var ix = 0
//      while (ix < read) {
//        if (buffer(ix) == 0x0a) {
//          pBuf += buffer.slice(prevEnd,ix)
//          prevEnd = ix
//        }
//        ix += 1
//      }
//
//			processed += read
//
//
//      // procBuffer.put(buffer, 0, read)
//      // procBuffer.clear()
//
//			if (processed % 1024*1024 == 0) {
//				logger.debug(s"Processed ${processed} bytes so far of ${cLength}")
//			}
//
//		}

		//logger.info(s"Finished with ${uri.toString}. Processed: ${processed} of ${cLength}")
	}

	public static class ByteBufferBackedInputStream extends InputStream{

  ByteBuffer buf;
  ByteBufferBackedInputStream( ByteBuffer buf){
    this.buf = buf;
  }
  public synchronized int read() throws IOException {
    if (!buf.hasRemaining()) {
      return -1;
    }
    return buf.get();
  }
  public synchronized int read(byte[] bytes, int off, int len) throws IOException {
    len = Math.min(len, buf.remaining());
    buf.get(bytes, off, len);
    return len;
  }
}
class ByteBufferBackedOutputStream extends OutputStream{
  ByteBuffer buf;
  ByteBufferBackedOutputStream( ByteBuffer buf){
    this.buf = buf;
  }
  public synchronized void write(int b) throws IOException {
    buf.put((byte) b);
  }

  public synchronized void write(byte[] bytes, int off, int len) throws IOException {
    buf.put(bytes, off, len);
  }

}

}
