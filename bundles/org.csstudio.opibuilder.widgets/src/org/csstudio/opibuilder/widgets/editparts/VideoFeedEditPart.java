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

import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.javase.scale.AWTUtil;
import org.jcodec.api.JCodecException;

/**
 * EditPart controller for the video feed widget.
 *
 * @author Sven Thoennissen - Space Applications Services
 */
public final class VideoFeedEditPart extends AbstractPVWidgetEditPart {
	
	private final boolean HAVE_DEBUG_OUTPUT = false; // true = show more debug infos in status text
	
	private List<String> textLines;
	
	private VideoStreamH264ES videoTrack = new VideoStreamH264ES();
	private VideoH264Adaptor h264Adaptor = null;
	private int prevSeqCount = -1;

	/**
	 * Constructor.
	 */
	public VideoFeedEditPart() {
		super();

		textLines = new LinkedList<String>();
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
		setPropertyChangeHandler(AbstractPVWidgetModel.PROP_PVVALUE, handler);
	}

	/**
	 * Overridden but not doing anything else.
	 * Called when the widget is created.
	 */
	@Override
	public void activate() {
		super.activate();
	}
	
	/**
	 * Called when the widget is deleted.
	 * Dispose of resources.
	 */
	@Override
	public void deactivate() {
		super.deactivate();
		getVideoFeedFigure().dispose();
	}
	
	// MARK: - Helpers
	
	/**
	 * Convenience method to return the model cast to the relevant class.
	 */
	protected VideoFeedModel getVideoFeedModel() {
		return (VideoFeedModel) getWidgetModel();
	}
	
	/**
	 * Convenience method to return the model cast to the relevant class.
	 *
	 * @return An instance of VideoFeedFigure.
	 */
	protected VideoFeedFigure getVideoFeedFigure() {
		return (VideoFeedFigure) getFigure();
	}

	/**
	 * Convenience method to show text in an overlay in front of the video.
	 * Use this method to show status text in production environment.
	 *
	 * @param text Line of text to display.
	 */
	protected void setFigureText(String text) {
		if (HAVE_DEBUG_OUTPUT) {
			textLines.add(text);
			while (textLines.size() > 10) {
				textLines.remove(0);
			}
			getVideoFeedFigure().setDataDescription(String.join("\n", textLines));
		} else {
			getVideoFeedFigure().setDataDescription(text);
		}
	}
	
	/**
	 * Convenience method to show debugging text in an overlay in front of the video.
	 * Use this method to show debugging text in test environment.
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
	 * Utility method to convert a hex string (e.g. 0x1419abf47529db) to a ByteBuffer.
	 *
	 * @return ByteBuffer that contains converted binary bytes.
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
	 * This method extracts the video sequence counter and reset the decoder if the counter is not contiguous.
	 * Forward video data to the decoder.
	 */
	protected void handleVideoPacket(Object newValue) {
		String textValue = newValue.toString();
		if (textValue.length() < 8 || !textValue.startsWith("0x")) {
			debugOutput("Not a proper video packet (" + textValue.length() + "), " + textValue.substring(0, 10));
			return;
		}
		try {
			int seqCount = Integer.parseUnsignedInt(textValue.substring(2, 6), 16);
			int vidLength = Integer.parseUnsignedInt(textValue.substring(6, 8), 16);
			ByteBuffer bb = hexStringToByteBuffer(textValue.substring(8, 8 + vidLength*2));

			// Do not check sequence counter if this is the first packet.
			if (prevSeqCount != -1) {
				int expectedCount = (prevSeqCount + 1) & 0xffff;
				if (expectedCount != seqCount) {
					// Jump detected -> stream is interrupted, we need to reset the decoder and streamer, and wait for new SPS/PPS units
					setFigureText(String.format("Sequence counter jumped (%d -> %d), waiting for good frame", prevSeqCount, seqCount));
					h264Adaptor = null;
					videoTrack.reset();
				}
			}
			prevSeqCount = seqCount;
			getVideoFeedFigure().setDetail(VideoDetailMap.PacketNo, String.valueOf(seqCount));
			feed(bb);
		} catch (NumberFormatException e) {
			debugOutput(String.format("Could not handle video packet: " + e.getMessage()));
		} catch (BufferOverflowException e) {
			debugOutput("Could not inject buffer, reseting stream and decoder");
			h264Adaptor = null;
			videoTrack.reset();
		} catch (JCodecException e) {
			getVideoFeedFigure().setDetail(VideoDetailMap.Decode, "-");
			getVideoFeedFigure().setDetail(VideoDetailMap.FrameNo, "-");
			getVideoFeedFigure().setDetail(VideoDetailMap.Resolution, "-");
			getVideoFeedFigure().setDetail(VideoDetailMap.ColorSpace, "-");
		}
	}
	
	/**
	 * Forward the video data chunk to the video decoder.
	 * This method reads the next video frame if any, and decodes it.
	 * The frame must have valid SPS/PPS NAL units in order to be able to decode the frame properly.
	 * The frame is converted to a BufferedImage (AWT) and forwarded to the figure.
	 *
	 * @param bb Binary data to be decoded.
	 * @throws java.nio.BufferOverflowException if the injected buffer could not be stored
	 */
	protected void feed(ByteBuffer bb) throws BufferOverflowException, JCodecException
	{
		videoTrack.injectChunk(bb);

		Packet frame = videoTrack.nextFrame();
		
		if (frame != null) {

			long time1 = System.currentTimeMillis();
			
			// Create new decoder if there is a useful frame
			if (h264Adaptor == null) {
				// Score: +60 = contains image, +20 = contains SPS, +20 = contains PPS
				int score = H264Decoder.probe(frame.getData());
				if (score == 100) {
					if (HAVE_DEBUG_OUTPUT) {
						debugOutput("Allocating new H264 decoder with healthy packet");
					} else {
						setFigureText(""); // Remove text
					}
					h264Adaptor = new VideoH264Adaptor(frame.getData());
				} else {
					// Not a H264 packet, or missing PPS/SPS NALUs
//					debugOutput("Could not decode frame, waiting for more data");
				}
			}
			if (h264Adaptor != null) {
				Picture pic = h264Adaptor.decodeFrame(frame);
				if (pic != null) {
					BufferedImage image = AWTUtil.toBufferedImage(pic);
//					debugOutput("feed mark 10 " + pic.getWidth() + "x" + pic.getHeight() + " " + image.getColorModel().getClass().getName());
					getVideoFeedFigure().setVideoData(image);

					// Set some details to be displayed
					long time2 = System.currentTimeMillis();
					getVideoFeedFigure().setDetail(VideoDetailMap.Decode, String.format("%.3f", (double)(time2 - time1)*0.001));
					getVideoFeedFigure().setDetail(VideoDetailMap.FrameNo, String.valueOf(frame.getFrameNo()));
					getVideoFeedFigure().setDetail(VideoDetailMap.Resolution, pic.getWidth() + "x" + pic.getHeight());
					getVideoFeedFigure().setDetail(VideoDetailMap.ColorSpace, pic.getColor().toString());
				} else {
					debugOutput("Could not decode image " + frame.getFrameNo());
					h264Adaptor = null;
				}
			}
			
		} // no frame
		
	}
}
