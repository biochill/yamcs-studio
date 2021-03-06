package org.csstudio.opibuilder.widgets.editparts;

import org.csstudio.opibuilder.editparts.AbstractPVWidgetEditPart;
import org.csstudio.opibuilder.properties.IWidgetPropertyChangeHandler;
import org.csstudio.opibuilder.model.AbstractPVWidgetModel;
import org.csstudio.opibuilder.model.AbstractWidgetModel;
import org.csstudio.opibuilder.widgets.figures.VideoDetailMap;
import org.csstudio.opibuilder.widgets.figures.VideoFeedFigure;
import org.csstudio.opibuilder.widgets.model.VideoFeedModel;
import org.diirt.vtype.VString;

import org.eclipse.draw2d.IFigure;
import org.eclipse.swt.widgets.Display;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.BufferOverflowException;
import java.util.List;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.Frame;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.common.model.Packet;
import org.jcodec.javase.scale.AWTUtil;
import org.jcodec.api.JCodecException;

/**
 * EditPart controller for the video feed widget.
 *
 * @author Sven Thoennissen - Space Applications Services
 */
public final class VideoFeedEditPart extends AbstractPVWidgetEditPart {
	
	// Video feed widget settings
	private final boolean HAVE_DEBUG_OUTPUT = false; // true = show more debug infos in widget
	private final boolean HAVE_PACKETLOSS_MESSAGE_IN_DISPLAY = true; // true = show packetloss message in widget

	private List<String> textLines;
	
	private VideoStreamH264ES videoTrack;
	private VideoH264Adaptor h264Adaptor = null;
	private int prevSeqCount = -1;
	private FrameTask frameTask;
	private Timer frameTimer;

	/**
	 * Constructor.
	 */
	public VideoFeedEditPart() {
		super();

		textLines = new LinkedList<String>();
		videoTrack = new VideoStreamH264ES();
	}

	// MARK: - Overrides

	/**
	 * Called by the editor to create the figure.
	 *
	 * @return Instance of VideoFeedFigure
	 */
	@Override
	protected IFigure doCreateFigure() {
		final VideoFeedFigure figure = new VideoFeedFigure();
		figure.setDetailsVisible(getVideoFeedModel().getDetailsVisible());
		return figure;
	}

	/**
	 * Called by the editor to install change handlers.
	 * A handler is installed to receive PV updates.
	 */
	@Override
	protected void registerPropertyChangeHandlers() {
		IWidgetPropertyChangeHandler handler = new IWidgetPropertyChangeHandler() {
			@Override
			public boolean handleChange(Object oldValue, Object newValue, final IFigure figure) {
				if (newValue == null)
					return false;
				handleVideoPacket(newValue);
				return false;
			}
		};
		setPropertyChangeHandler(AbstractPVWidgetModel.PROP_PVVALUE, handler, true);
	}

	/**
	 * Overridden but not doing anything else.
	 * Called when the widget is created.
	 */
	@Override
	public void activate() {
		super.activate();
		frameTimer = new Timer();
	}
	
	/**
	 * Called when the widget is deleted.
	 * Dispose of resources.
	 */
	@Override
	public void deactivate() {
		super.deactivate();
		getVideoFeedFigure().dispose();

		if (frameTimer != null) {
			frameTimer.cancel();
			frameTimer = null;
		}
		frameTask = null;
	}
	
	// MARK: - Helpers
	
	/**
	 * Convenience method to return the model cast to the relevant class.
	 *
	 * @return An instance of VideoFeedModel.
	 */
	protected VideoFeedModel getVideoFeedModel() {
		return (VideoFeedModel) getWidgetModel();
	}

	/**
	 * Convenience method to return the figure cast to the relevant class.
	 *
	 * @return An instance of VideoFeedFigure.
	 */
	protected VideoFeedFigure getVideoFeedFigure() {
		return (VideoFeedFigure) getFigure();
	}

	/**
	 * Show text in an overlay at the top of the video.
	 * Use this method to show status text in production environment.
	 *
	 * @param text Line of text to display.
	 */
	protected void setFigureText(String text) {
		if (HAVE_DEBUG_OUTPUT) {
			// Test environment: Show the last 10 lines
			textLines.add(text);
			while (textLines.size() > 10) {
				textLines.remove(0);
			}
			getVideoFeedFigure().setDataDescription(String.join("\n", textLines));
		} else {
			// Production environment: Show only the last line
			getVideoFeedFigure().setDataDescription(text);
		}
	}
	
	/**
	 * Convenience method to show debugging text in an overlay in front of the video.
	 * Use this method to show debugging text in test environment.
	 * In production this method does not show any text.
	 * It buffers a number of text lines so the overlay shows the last couple of text lines.
	 *
	 * @param text Line of text to display.
	 */
	protected void debugOutput(String text) {
		if (HAVE_DEBUG_OUTPUT) {
			setFigureText(text);
		}
	}
	
	/**
	 * Utility method to convert a hex string (e.g. 1419abf47529db) to a ByteBuffer.
	 *
	 * @param s String that contains a hex string, not including the leading "0x"
	 * @return ByteBuffer that contains converted bytes.
	 */
	public static ByteBuffer hexStringToByteBuffer(String s) throws NumberFormatException {
		if ((s.length() & 1) == 1) {
			s = "0" + s;
		}
		ByteBuffer bb = ByteBuffer.allocate(s.length()/2);
		for (int i = 0; i < s.length(); i += 2) {
			bb.put((byte)Integer.parseUnsignedInt(s.substring(i, i + 2), 16));
		}
		bb.flip();
		return bb;
	}
	
	/**
	 * Parse and validate the PV value, and forward the video data to the decoder.
	 * This method extracts the video sequence counter and checks for continuity (packet loss based on this counter).
	 * The video data is added to an internal buffer (videoTrack).
	 * If a full H.264 packet (containing a frame) is available, it is taken from the internal buffer and forwarded to the decoder.
	 *
	 * @param newValue Content of the PV, typically VType instance
	 */
	protected void handleVideoPacket(Object newValue) {
		String textValue = newValue.toString();
		// We expect a hexstring with leading "0x".
		if (textValue.length() < 8 || !textValue.startsWith("0x")) {
			debugOutput("Not a proper video packet (" + textValue.length() + "), " + textValue.substring(0, 10));
			return;
		}
		try {
			// String layout: 0xssssnndddddddd...
			// s = sequence counter
			// n = number of video data bytes to follow
			// d = data bytes (max 253 bytes)
			int seqCount = Integer.parseUnsignedInt(textValue.substring(2, 6), 16); // unsigned 16-bit counter
			int vidLength = Integer.parseUnsignedInt(textValue.substring(6, 8), 16); // unsigned 8-bit length
			ByteBuffer bb = hexStringToByteBuffer(textValue.substring(8, 8 + vidLength*2));

			// Do not check sequence counter if this is the very first packet.
//			debugOutput("Received packet " + seqCount);
			if (prevSeqCount != -1) {
				int expectedCount = (prevSeqCount + 1) & 0xffff;
				if (expectedCount != seqCount) {
					// Jump detected -> stream is interrupted, try to salvage what's in the buffer
					if (HAVE_PACKETLOSS_MESSAGE_IN_DISPLAY) {
						setFigureText(String.format("Sequence counter jumped (%d -> %d)", prevSeqCount, seqCount));
					}

					// Attempt to decode a frame from the buffer before we flush it
					// Normally we are waiting for the next (non-)IDR frame to capture the frame-sequence
					// but it is lost due to packet loss, so we try to decode the packet with the current (non-)IDR frame(s).
					Packet packet = videoTrack.packetFromCurrentData();
					if (packet != null) {
						processStreamPacket(packet);
					}

					// Forget everything that's left in the video buffer. We have to wait for the next NALU marker.
					debugOutput("  Flushing buffer");
					videoTrack.resetBuffer();
				}
			}
			prevSeqCount = seqCount;

			getVideoFeedFigure().setDetail(VideoDetailMap.PACKETNO, String.valueOf(seqCount));

			// Add chunk data to video buffer, then attempt to read next frame
			videoTrack.injectChunk(bb);
			Packet packet = videoTrack.nextFrame();
			if (packet != null) {
				processStreamPacket(packet);
			}

		} catch (NumberFormatException e) {
			debugOutput(String.format("Could not handle video packet: " + e.getMessage()));
		} catch (BufferOverflowException e) {
			debugOutput("Could not inject buffer, reseting stream and decoder");
			h264Adaptor = null;
			videoTrack.resetBuffer();
		} catch (JCodecException e) {
			setFigureText("Could not decode: " + e.getMessage());
			h264Adaptor = null;
			getVideoFeedFigure().setDetail(VideoDetailMap.DECODE, "-");
			getVideoFeedFigure().setDetail(VideoDetailMap.FRAMENO, "-");
			getVideoFeedFigure().setDetail(VideoDetailMap.RESOLUTION, "-");
			getVideoFeedFigure().setDetail(VideoDetailMap.COLORSPACE, "-");
		}
	}
	
	/**
	 * Forward the video data chunk to the video decoder.
	 * This method decodes the given packet into a frame. A packet is a sequence of NAL units extracted from the video buffer.
	 * If the decoder is currently null, the packet must include valid SPS/PPS NAL units in order to be able to decode the frame properly.
	 * The frame is converted to a BufferedImage (AWT) and forwarded to the figure.
	 *
	 * @param packet JCodec Packet to be decoded
	 * @throws java.nio.BufferOverflowException if the injected buffer could not be stored
	 */
	protected void processStreamPacket(Packet packet) throws BufferOverflowException, JCodecException
	{
		// Create new decoder if there is a useful packet
		if (h264Adaptor == null) {
			// Score: +60 = contains image, +20 = contains SPS, +20 = contains PPS
			int score = H264Decoder.probe(packet.getData());
			// score = 60 -> frame is present
			// score = +20 -> SPS is present
			// score = +20 -> PPS is present
			if (score == 100) {
				// Frame + SPS/PPS are present! This is needed to initialize the decoder properly.
				if (HAVE_DEBUG_OUTPUT) {
					debugOutput("Allocating new H264 decoder with healthy packet");
				} else {
				}
				h264Adaptor = new VideoH264Adaptor(packet.getData());
				// Set the FPS value in the detail map as found in the optional VUI data from the SPS NALU.
				getVideoFeedFigure().setVideoFPS(videoTrack.fps);
			} else {
				// Not a H264 packet, or missing PPS/SPS NALUs
				debugOutput("Could not decode packet, waiting for more data");
			}
		}

		if (h264Adaptor == null)
			return;

		if (videoTrack.ignoreBFrames) {
			// Just decode and display the next frame, do not perform any frame rate timing
			
			long time1 = System.currentTimeMillis();
			Frame pic = h264Adaptor.decodePacket(packet); // can throw JCodecException
			long time2 = System.currentTimeMillis();
			getVideoFeedFigure().setDetail(VideoDetailMap.DECODE, String.format("%.3f", (double)(time2 - time1)*0.001));

			if (HAVE_PACKETLOSS_MESSAGE_IN_DISPLAY) {
				if (pic.getFrameType() == SliceType.I) {
					setFigureText(""); // Remove "Sequence counter jumped" status text in widget
				}
			} else {
				setFigureText(""); // Remove "Waiting for video" status text in widget
			}

			// Do not decode B frames, we're expecting a live stream, so we decode only I and P frames
			if (pic.getFrameType() != SliceType.B) {
				final BufferedImage image = AWTUtil.toBufferedImage(pic);
				getVideoFeedFigure().setVideoData(image);

				// Set some details to be displayed
				getVideoFeedFigure().setDetail(VideoDetailMap.FRAMENO, String.valueOf(pic.getFrameNo()));
				getVideoFeedFigure().setDetail(VideoDetailMap.RESOLUTION, image.getWidth() + "x" + image.getHeight());
				getVideoFeedFigure().setDetail(VideoDetailMap.COLORSPACE, pic.getColor().toString());
			}

		} else {
			// Decode and display according to fps timing
			
			long time1 = System.currentTimeMillis();
			h264Adaptor.addPacket(packet);
			long time2 = System.currentTimeMillis();
			getVideoFeedFigure().setDetail(VideoDetailMap.DECODE, String.format("%.3f", (double)(time2 - time1)*0.001));
			
			// Start new timer task to show new images. Otherwise wait until current time task will show it.
			if (frameTask == null && h264Adaptor.hasNextFrame()) {
				startSchedule();
			}
		}
	} // func
	
	public class FrameTask extends TimerTask {
		// This class is used only for fps timing

		@Override
		public void run() {
			final Frame pic = h264Adaptor.getNextFrame();
			if (pic != null) {

				final BufferedImage image = AWTUtil.toBufferedImage(pic);
				
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						try {
							getVideoFeedFigure().setVideoData(image);
							// Set some details to be displayed
							getVideoFeedFigure().setDetail(VideoDetailMap.FRAMENO, String.valueOf(pic.getFrameNo()));
							getVideoFeedFigure().setDetail(VideoDetailMap.RESOLUTION, image.getWidth() + "x" + image.getHeight());
							getVideoFeedFigure().setDetail(VideoDetailMap.COLORSPACE, pic.getColor().toString());
						} catch (JCodecException e) {
							debugOutput(e.getMessage());
						}
					}
				});

			} else {
				cancel();
				frameTask = null;
			}

		} // run
	} // class
	
	// Method used only for fps timing
	void startSchedule() {
		double fps = videoTrack.fps > 0.01 ? videoTrack.fps : 5;
		long intervalMillis = (long)(1000.0/fps);
		frameTask = new FrameTask();
		frameTimer.scheduleAtFixedRate(frameTask, 0, intervalMillis);
	}

}
