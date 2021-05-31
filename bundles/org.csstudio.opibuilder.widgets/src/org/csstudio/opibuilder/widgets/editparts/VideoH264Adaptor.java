package org.csstudio.opibuilder.widgets.editparts;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Collections;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.nio.ByteBuffer;

import org.jcodec.api.specific.ContainerAdaptor;
import org.jcodec.api.MediaInfo;
import org.jcodec.api.JCodecException;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.codecs.h264.io.model.Frame;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.TrackType;
import org.jcodec.common.Codec;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Size;

/**
 * This class was copied and modified from AVCMP4Adaptor (JCodec).
 * It is a wrapper for the decoder.
 *
 * @author Sven Thoennissen - Space Applications Services
 */
public class VideoH264Adaptor implements ContainerAdaptor
{
	protected H264Decoder decoder;
	protected VideoCodecMeta vMeta;
	protected LinkedList<ArrayList<Frame>> orderedFrames;
	protected int expectedPoc;

	/**
	 * Constructor.
	 *
	 * @param codecSpecific H.264 frame for initial analysis
	 */
	VideoH264Adaptor(ByteBuffer codecSpecific) {
		decoder = H264Decoder.createH264DecoderFromCodecPrivate(codecSpecific);
		vMeta = decoder.getCodecMeta(codecSpecific);
		orderedFrames = new LinkedList<>();
		expectedPoc = 0;
	}

	/**
	 * Adds a H.264 packet to the internal queue.
	 * It also maintains the display order of the GOP.
	 *
	 * This method is used only if decoding of B-frames is enabled in the editpart.
	 *
	 * @param packet a H.264 packet
	 * @throws JCodecException
	 */
	synchronized void addPacket(Packet packet) throws JCodecException {
		Frame frame = decodePacket(packet);
		if (frame == null)
			throw new JCodecException("Could not decode packet " + packet.getFrameNo());

		// Is a key frame? -> Create new GOP and add it to the list
		if (frame.getPOC() == 0) {
			ArrayList<Frame> gop = new ArrayList<Frame>();
			gop.add(frame);
			orderedFrames.offer(gop);
			
			// Remove empty GOPs until we find the first non-empty one.
			while (!orderedFrames.isEmpty()) {
				gop = orderedFrames.getFirst();
				if (gop.isEmpty()) {
					orderedFrames.removeFirst();
					expectedPoc = 0;
				} else {
					break;
				}
			}
		} else {
			// Add non-key frame and sort the array
			ArrayList<Frame> gop = orderedFrames.getLast();
			gop.add(frame);
			sortByDisplay(gop);
		}
	}
	
	/**
	 * Decodes a H.264 frame and returns an image.
	 *
	 * @param packet a H.264 packet
	 * @return Frame with image data
	 * @throws JCodecException
	 */
	public Frame decodePacket(Packet packet) throws JCodecException {
		if (vMeta == null) {
			vMeta = decoder.getCodecMeta(packet.getData());
		}
		Frame frame = decoder.decodeFrame(packet.getData(), allocatePicture());
		if (frame == null)
			throw new JCodecException("Could not decode packet " + packet.getFrameNo());
		return frame;
	}
	
	/**
	 * Sort the GOP list by display order. (POC = Picture Order Count)
	 *
	 * @param gop an array of H.264 frames
	 */
	protected void sortByDisplay(ArrayList<Frame> gop) {
		Collections.sort(gop, new Comparator<Frame>() {
			@Override
			public int compare(Frame o1, Frame o2) {
				return o1.getPOC() > o2.getPOC() ? 1 : (o1.getPOC() == o2.getPOC() ? 0 : -1);
			}
		});
	}

	/**
	 * Indicates whether there is a frame available.
	 * There could be frames with future POC but this method indicates if a frame with the next expected POC is available.
	 *
	 * @return true if there is a frame available, or false if there is no frame available.
	 */
	synchronized boolean hasNextFrame() {
		if (!orderedFrames.isEmpty()) {
			ArrayList<Frame> gop = orderedFrames.getFirst();
			if (!gop.isEmpty()) {
				// Get the next frame and check its POC
				Frame frame = gop.get(0);
				return frame.getPOC() == expectedPoc;
			}
		}
		return false;
	}

	/**
	 * Return the next expected frame.
	 *
	 * @return frame at expected POC, or null if there is no frame available.
	 */
	synchronized Frame getNextFrame() {
		if (!orderedFrames.isEmpty()) {
			ArrayList<Frame> gop = orderedFrames.getFirst();
			if (!gop.isEmpty()) {
				// Get the next frame and check its POC
				Frame frame = gop.get(0);
				if (frame.getPOC() == expectedPoc) {
					gop.remove(0);
					expectedPoc += 2;
					return frame;
				}
			}
		}
		return null;
	}

	// MARK: - Overrides
	
	/**
	 * Decodes a H.264 frame and returns a Picture that uses the given bitmap data.
	 *
	 * @param packet a H.264 frame
	 * @param data    array to be used for the returned Picture
	 * @return      Picture with image data
	 */
	@Override
	public Picture decodeFrame(Packet packet, byte[][] data) {
		return decoder.decodeFrame(packet.getData(), data);
	}
	
	/**
	 * Overridden method to tell that the stream does not support seeking.
	 * This method is unused but the interface requires it.
	 *
	 * @param pkt a H.264 frame
	 * @return always false
	 */
	@Override
	public boolean canSeek(Packet pkt) {
		return false;
	}
	
	/**
	 * Allocate an array that can hold image data according to dimensions received in the metadata.
	 *
	 * @return byte array sized according to the metadata allocated in the constructor
	 */
	@Override
	public byte[][] allocatePicture() {
		Size size = vMeta.getSize();
		return Picture.create(size.getWidth(), size.getHeight(), vMeta.getColor()).getData();
	}
	
	/**
	 * Overridden method to return a MediaInfo instance.
	 * This method is unused but the interface requires it.
	 *
	 * @return        Initialiized MediaInfo instance.
	 * @see           MediaInfo
	 */
	@Override
	public MediaInfo getMediaInfo() {
		return new MediaInfo(vMeta.getSize());
	}
}
