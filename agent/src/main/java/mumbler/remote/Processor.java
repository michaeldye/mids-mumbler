package mumbler.remote;

import java.util.List;
import java.util.LinkedList;
import java.util.AbstractQueue;
import java.util.concurrent.*;

import java.util.stream.*;
import java.util.function.Consumer;

import java.util.concurrent.LinkedTransferQueue;
import java.io.*;
import java.util.Arrays;
import java.net.URI;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.http.HttpEntity;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

// must be a singleton, not threadsafe
public class Processor {

  private final ExecutorService executor;

  private final AbstractQueue<String> statsQueue;

  private final URI uri;

  private static final Logger logger = LoggerFactory.getLogger(Processor.class);

  private static final int BUFFER_SIZE = 1024*1024*40;

  // shared buffer, dangerous but fast
  private static final byte[] BUFFER = new byte[BUFFER_SIZE];

  public Processor(ExecutorService pool, URI uri, AbstractQueue<String> statsQueue) throws IOException {
    this.statsQueue = statsQueue;
    this.uri = uri;

    executor = pool;
  }

/*
      val outStr = new PipedOutputStream()
      val inStr = new PipedInputStream(outStr, 800*1024*1024);

			val bufSize = 9100

			val response = HttpClients.createDefault().execute(new HttpGet(uri.toURL.toString))
			val entity = response.getEntity
			val instream = entity.getContent
			val cLength = entity.getContentLength

      val statsQueue = new java.util.concurrent.LinkedTransferQueue[java.lang.String]()
			// processingThreadPool.execute(new PreProcessor(outStr, uri, bufSize, cLength, outstreamBld))


      streamingDownloadThreadPool.execute(new Runnable {
    	  val buffer = new Array[Byte](bufSize)

        def run() {
          try {
			      logger.info(s"Locally caching data from ${uri.toString}")
			      var xferred: Long = 0
			      while (xferred < cLength) {
              val read = instream.read(buffer)

			      	if (xferred != 0 && xferred % (1024*1024*512) == 0) logger.info(s"Read from HTTP stream ${xferred} bytes so far of ${cLength} for ${uri.toString}")
              outStr.write(buffer, 0, read)
              xferred += read
			      }

          } catch {
			    	case e: Exception => logger.error(e.toString)
			    } finally {

			      instream.close()
			      outStr.close()
			      response.close()
			    }
        }
      })
*/

  public Future<Boolean> process() {

//    Runnable downloader = () -> {
//
//    }

    Callable<Stream<String>> preprocessor = () -> {

      // preprocess, get the stream and then parallel process it
      Stream<String> pre = preprocess();
      return pre;
    };

//    executor.submit(downloader);
    Future<Stream<String>> fut =  executor.submit(preprocessor);

    Callable<Boolean> postprocessor = () -> {
      try {
        // blocks until complete
        Stream<String> preprocessed = fut.get();

        Consumer<String> lines = line -> {
          logger.debug("Line: {}", line);
        };

        preprocessed.forEach(lines);

        // as an alternative to connecting the http client and processor by a pipe, return a future now
		  	return true;

      } catch (InterruptedException e) {
		  	e.printStackTrace();
		  } catch (ExecutionException e) {
		  	e.printStackTrace();
		  }

      return false;
    };

	  return executor.submit(postprocessor);
  }

  // reads from PipedInputStream until exhausted, splitting on 0x0a boundaries
  public Stream<String> preprocess() throws java.net.MalformedURLException {
    logger.info("Starting preprocessing {}", uri.toString());

    long totalProcessed = 0;

    Stream.Builder<String> preProcessed = Stream.builder();

    try {
		  CloseableHttpResponse response = HttpClients.createDefault().execute(new HttpGet(uri.toURL().toString()));
		  HttpEntity entity = response.getEntity();
		  long cLength = entity.getContentLength();
		  InputStream instream = entity.getContent();

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
          int read = instream.read(BUFFER, 0, BUFFER_SIZE);

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

                  // reductionQueue.add(new String(Arrays.copyOfRange(BUFFER, prevIx, gramEndIx)));
                  preProcessed.accept(new String(Arrays.copyOfRange(BUFFER, prevIx, gramEndIx)));
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
        logger.error("Failed to process entity inputstream", ex);
      } finally {
        // important!
        response.close();
      }

    } catch (java.io.IOException ex) {
      logger.error("HTTP Client failure", ex);
    }

    logger.info("Finished with {}", uri.toString());
    return preProcessed.build();
  }
}
