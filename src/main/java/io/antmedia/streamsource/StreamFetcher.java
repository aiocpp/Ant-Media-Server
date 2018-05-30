package io.antmedia.streamsource;

import static org.bytedeco.javacpp.avcodec.av_packet_free;
import static org.bytedeco.javacpp.avcodec.av_packet_ref;
import static org.bytedeco.javacpp.avcodec.av_packet_unref;
import static org.bytedeco.javacpp.avformat.av_read_frame;
import static org.bytedeco.javacpp.avformat.avformat_close_input;
import static org.bytedeco.javacpp.avformat.avformat_find_stream_info;
import static org.bytedeco.javacpp.avformat.avformat_open_input;
import static org.bytedeco.javacpp.avutil.av_dict_free;
import static org.bytedeco.javacpp.avutil.av_dict_set;
import static org.bytedeco.javacpp.avutil.av_rescale_q;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.bytedeco.javacpp.avutil.AVRational;
import org.red5.server.api.scheduling.IScheduledJob;
import org.red5.server.api.scheduling.ISchedulingService;
import org.red5.server.api.scope.IScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.AppSettings;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.muxer.MuxAdaptor;
import io.antmedia.rest.model.Result;

public class StreamFetcher {

	protected static Logger logger = LoggerFactory.getLogger(StreamFetcher.class);
	private Broadcast stream;
	private WorkerThread thread;
	/**
	 * Connection setup timeout value
	 */
	private int timeout;
	public boolean exceptionInThread = false;

	/**
	 * Last packet received time
	 */
	private long lastPacketReceivedTime = 0;
	private boolean threadActive = false;
	private Result cameraError=new Result(false,"");
	private static final int PACKET_RECEIVED_INTERVAL_TIMEOUT = 3000;
	private IScope scope;
	private AntMediaApplicationAdapter appInstance;
	private long[] lastDTS;
	private long[] lastPTS;
	private MuxAdaptor muxAdaptor = null;

	/**
	 * If it is true, it restarts fetching everytime it disconnects
	 * if it is false, it does not restart
	 */
	private boolean restartStream = true;

	/**
	 * Buffer time in milliseconds
	 */
	private int bufferTime = 0;
	
	private ConcurrentLinkedQueue<AVPacket> availableBufferQueue = new ConcurrentLinkedQueue<>();

	private ISchedulingService scheduler;
	private AVRational avRationalTimeBaseMS;
	private AppSettings appSettings;

	public StreamFetcher(Broadcast stream, IScope scope, ISchedulingService scheduler) throws Exception {
		if (stream == null || stream.getStreamId() == null || stream.getStreamUrl() == null) {
			throw new Exception("Stream is not initialized properly. Check stream("+stream+"), "
					+ " stream id(" + stream.getStreamId() + ") and stream url("+ stream.getStreamUrl() +") values");
		}
		this.stream = stream;
		this.scope = scope;
		this.scheduler = scheduler;
		
		
		if (getAppSettings() == null) {
			throw new Exception("App Settings is null in StreamFetcher");
		}
		
		this.bufferTime = getAppSettings().getStreamFetcherBufferTime();

		avRationalTimeBaseMS = new AVRational();
		avRationalTimeBaseMS.num(1);
		avRationalTimeBaseMS.den(1000);

		logger.debug(":::::::::::scope is ::::::::" + String.valueOf(scope));

	}

	public Result prepareInput(AVFormatContext inputFormatContext) {

		setConnectionTimeout(5000);

		Result result = new Result(false);
		if (inputFormatContext == null) {
			logger.info("cannot allocate input context");
			return result;
		}

		AVDictionary optionsDictionary = new AVDictionary();

		String streamUrl = stream.getStreamUrl();
		if (streamUrl.startsWith("rtsp://")) {
			av_dict_set(optionsDictionary, "rtsp_transport", "tcp", 0);
		}

		String timeout = String.valueOf(this.timeout);
		av_dict_set(optionsDictionary, "stimeout", timeout, 0);

		int ret;

		logger.debug("stream url: {}  " , stream.getStreamUrl());

		if ((ret = avformat_open_input(inputFormatContext, stream.getStreamUrl(), null, optionsDictionary)) < 0) {

			byte[] data = new byte[1024];
			avutil.av_strerror(ret, data, data.length);

			String errorStr=new String(data, 0, data.length);

			result.setMessage(errorStr);		

			logger.debug("cannot open input context with error:: {}",  result.getMessage());
			return result;
		}

		av_dict_free(optionsDictionary);

		ret = avformat_find_stream_info(inputFormatContext, (AVDictionary) null);
		if (ret < 0) {

			result.setMessage("Could not find stream information\n");
			logger.info(result.getMessage());
			return result;
		}

		lastDTS = new long[inputFormatContext.nb_streams()];
		lastPTS = new long[lastDTS.length];

		for (int i = 0; i < lastDTS.length; i++) {
			lastDTS[i] = -1;
			lastPTS[i] = -1;
		}

		result.setSuccess(true);
		return result;

	}

	public Result prepare(AVFormatContext inputFormatContext) {
		Result result = prepareInput(inputFormatContext);

		setCameraError(result);

		return result;

	}

	public class WorkerThread extends Thread implements IScheduledJob {

		private volatile boolean stopRequestReceived = false;

		private volatile boolean streamPublished = false;
		protected AtomicBoolean isJobRunning = new AtomicBoolean(false);
		AVFormatContext inputFormatContext = null;

		private volatile boolean buffering = false;
		private ConcurrentLinkedQueue<AVPacket> bufferQueue = new ConcurrentLinkedQueue<>();

		@Override
		public void run() {

			setThreadActive(true);
			long lastPacketTime = 0, firstPacketTime = 0, bufferDuration = 0;

			AVPacket pkt = null;
			String packetWriterJobName = null;
			try {
				inputFormatContext = new AVFormatContext(null); // avformat.avformat_alloc_context();
				pkt = avcodec.av_packet_alloc();
				//logger.info("before prepare");
				Result result = prepare(inputFormatContext);


				if (result.isSuccess()) {

					muxAdaptor = MuxAdaptor.initializeMuxAdaptor(null,true);

					muxAdaptor.init(scope, stream.getStreamId(), false);

					logger.info("{} stream count in stream {} is {}", stream.getStreamId(), stream.getStreamUrl(), inputFormatContext.nb_streams());

					if(muxAdaptor.prepareInternal(inputFormatContext)) {

						long currentTime = System.currentTimeMillis();
						muxAdaptor.setStartTime(currentTime);

						getInstance().startPublish(stream.getStreamId());

						if (bufferTime > 0) {
							packetWriterJobName = scheduler.addScheduledJob(5, this);
						}

						int bufferLogCounter = 0;
						while (true) {
							int ret = av_read_frame(inputFormatContext, pkt);
							if (ret < 0) {
								logger.info("cannot read frame from input context");
								break;
							}
							streamPublished = true;
							lastPacketReceivedTime = System.currentTimeMillis();

							/**
							 * Check that dts values are monotically increasing for each stream
							 */
							int packetIndex = pkt.stream_index();
							if (lastDTS[packetIndex] >= pkt.dts()) {
								pkt.dts(lastDTS[packetIndex] + 1);
							}
							lastDTS[packetIndex] = pkt.dts();
							if (pkt.dts() > pkt.pts()) {
								pkt.pts(pkt.dts());
							}

							if (lastPTS[packetIndex] >= pkt.pts()) {
								pkt.pts(lastPTS[packetIndex] + 1);
							}
							lastPTS[packetIndex] = pkt.pts();

							if (bufferTime > 0) 
							{
								AVPacket packet = getAVPacket();
								av_packet_ref(packet, pkt);
								bufferQueue.add(packet);
								
								AVPacket pktHead = bufferQueue.peek();
								lastPacketTime = av_rescale_q(pkt.pts(), inputFormatContext.streams(pkt.stream_index()).time_base(), avRationalTimeBaseMS);
								firstPacketTime = av_rescale_q(pktHead.pts(), inputFormatContext.streams(pktHead.stream_index()).time_base(), avRationalTimeBaseMS);
								bufferDuration = (lastPacketTime - firstPacketTime);

								if ( bufferDuration > bufferTime) {
									buffering = false;
								}
								
								bufferLogCounter++;
								if (bufferLogCounter % 100 == 0) {
									logger.info("Buffer status {}, buffer duration {}ms buffer time {}ms", buffering, bufferDuration, bufferTime);
									bufferLogCounter = 0;
								}
							}
							else {
								muxAdaptor.writePacket(inputFormatContext.streams(pkt.stream_index()), pkt);
							}
							av_packet_unref(pkt);
							if (stopRequestReceived) {
								logger.warn("breaking the loop");
								break;
							}
						}
					}

				}
				else {
					logger.debug("Prepare for " + stream.getName() + " returned false");
				}

				setCameraError(result);
			} 
			catch (OutOfMemoryError e) {
				e.printStackTrace();
				exceptionInThread  = true;
			}
			catch (Exception e) {
				logger.info("---Exception in thread---");
				e.printStackTrace();
				exceptionInThread  = true;
			} 
			
			if (packetWriterJobName != null) {
				logger.info("Removing packet writer job {}", packetWriterJobName);
				scheduler.removeScheduledJob(packetWriterJobName);
				packetWriterJobName = null;
			}
			
			writeAllBufferedPackets();
			

			if (muxAdaptor != null) {
				logger.info("Writing trailer for Muxadaptor");
				muxAdaptor.writeTrailer(inputFormatContext);
				muxAdaptor = null;
			}

			if (pkt != null) {
				av_packet_free(pkt);
				pkt = null;
			}
			
			if (inputFormatContext != null) {
				try {
					avformat_close_input(inputFormatContext);
				}
				catch (Exception e) {
					e.printStackTrace();
				}
				inputFormatContext = null;
			}

			if(streamPublished) {
				getInstance().closeBroadcast(stream.getStreamId());
				streamPublished=false;
			}


			setThreadActive(false);
			if(!stopRequestReceived && restartStream) {
				thread = new WorkerThread();
				thread.start();
			}

			logger.info("Leaving thread");

		}
		
		private void writeAllBufferedPackets() 
		{
			while (!bufferQueue.isEmpty()) {
				AVPacket pkt = bufferQueue.poll();
				muxAdaptor.writePacket(inputFormatContext.streams(pkt.stream_index()), pkt);
				av_packet_unref(pkt);
			}
			
			AVPacket pkt;
			while ((pkt = bufferQueue.poll()) != null) {
				pkt.close();
				pkt = null;
			}
		}
		
		public void setStopRequestReceived() {
			logger.warn("inside of setStopRequestReceived");
			stopRequestReceived = true;
		}

		public boolean isStopRequestReceived() {
			return stopRequestReceived;
		}

		@Override
		public void execute(ISchedulingService service) throws CloneNotSupportedException 
		{
			if (isJobRunning.compareAndSet(false, true)) 
			{
				if (!buffering) {
					AVPacket pkt = bufferQueue.poll();
					if (pkt != null) {
						muxAdaptor.writePacket(inputFormatContext.streams(pkt.stream_index()), pkt);
						av_packet_unref(pkt);
						availableBufferQueue.offer(pkt);
					}
					else {
						buffering = true;
					}
				}
				isJobRunning.compareAndSet(true, false);
			}
		}
	}

	public void startStream() {
		new Thread() {
			public void run() {
				try {
					int i = 0;
					while (threadActive) {
						Thread.sleep(100);
						if (i % 50 == 0) {
							logger.info("waiting for thread to be finished for stream " + stream.getStreamUrl());
							i = 0;
						}
						i++;
					}
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
					Thread.currentThread().interrupt();
				}

				exceptionInThread = false;
				thread = new WorkerThread();
				thread.start();
				logger.info("StartStream called, new thread is started");
			};
		}.start();

	}




	public AVPacket getAVPacket() {
		if (!availableBufferQueue.isEmpty()) {
			return availableBufferQueue.poll();
		}
		return new AVPacket();
	}

	/**
	 * If thread is alive and receiving packet with in the {@link PACKET_RECEIVED_INTERVAL_TIMEOUT} time
	 * mean it is running
	 * @return true if it is running and false it is not
	 */
	public boolean isStreamAlive() {
		return ((System.currentTimeMillis() - lastPacketReceivedTime) < PACKET_RECEIVED_INTERVAL_TIMEOUT);
	}

	public boolean isStopped() {
		return thread.isInterrupted();
	}

	public void stopStream() 
	{
		if (getThread() != null) {
			logger.warn("stop stream called");
			getThread().setStopRequestReceived();

		}else {
			logger.warn("stop stream is called and thread is null");
		}
	}

	public boolean isStopRequestReceived() {
		return getThread().isStopRequestReceived();
	}

	public WorkerThread getThread() {
		return thread;
	}

	public void setThread(WorkerThread thread) {
		this.thread = thread;
	}

	public Broadcast getStream() {
		return stream;
	}

	public void restart() {
		stopStream();
		new Thread() {
			public void run() {
				try {
					while (threadActive) {
						Thread.sleep(100);
					}

					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
					Thread.currentThread().interrupt();
				}
				startStream();
			};
		}.start();

	}
	/**
	 * Set timeout when establishing connection
	 * @param timeout in ms
	 */
	public void setConnectionTimeout(int timeout) {
		this.timeout = timeout * 1000;
	}

	public boolean isExceptionInThread() {
		return exceptionInThread;
	}

	public void setThreadActive(boolean threadActive) {
		this.threadActive = threadActive;
	}

	public boolean isThreadActive() {
		return threadActive;
	}
	public Result getCameraError() {
		return cameraError;
	}

	public void setCameraError(Result cameraError) {
		this.cameraError = cameraError;
	}
	public IScope getScope() {
		return scope;
	}

	public void setScope(IScope scope) {
		this.scope = scope;
	}

	public AntMediaApplicationAdapter getInstance() {
		if (appInstance == null) {
			appInstance = (AntMediaApplicationAdapter) scope.getContext().getApplicationContext().getBean("web.handler");
		}
		return appInstance;
	}

	public MuxAdaptor getMuxAdaptor() {
		return muxAdaptor;
	}

	public void setMuxAdaptor(MuxAdaptor muxAdaptor) {
		this.muxAdaptor = muxAdaptor;
	}

	public boolean isRestartStream() {
		return restartStream;
	}

	public void setRestartStream(boolean restartStream) {
		this.restartStream = restartStream;
	}

	public void setStream(Broadcast stream) {
		this.stream = stream;
	}

	public int getBufferTime() {
		return bufferTime;
	}

	public void setBufferTime(int bufferTime) {
		this.bufferTime = bufferTime;
	}
	
	private AppSettings getAppSettings() {
		if (appSettings == null) {
			appSettings = (AppSettings) scope.getContext().getApplicationContext().getBean(AppSettings.BEAN_NAME);
		}
		return appSettings;
	}


}
