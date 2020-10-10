package org.csstudio.opibuilder.widgets.editparts;

import java.nio.ByteBuffer;

import org.jcodec.api.specific.ContainerAdaptor;
import org.jcodec.api.MediaInfo;
import org.jcodec.codecs.h264.H264Decoder;
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

	/**
	 * Constructor.
	 *
	 * @param codecSpecific H.264 frame for initial analysis
	 */
	VideoH264Adaptor(ByteBuffer codecSpecific) {
		decoder = H264Decoder.createH264DecoderFromCodecPrivate(codecSpecific);
		vMeta = decoder.getCodecMeta(codecSpecific);
	}

	/**
	 * Decodes a H.264 frame and returns an image.
	 *
	 * @param packet a H.264 frame
	 * @return      Picture with image data
	 */
	Picture decodeFrame(Packet packet) {
		if (vMeta == null) {
			vMeta = decoder.getCodecMeta(packet.getData());
		}
		return decodeFrame(packet, allocatePicture());
	}
	
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
	 * @return        always false
	 */
	@Override
	public boolean canSeek(Packet pkt) {
		return false;
	}
	
	/**
	 * Allocate an array that can hold image data according to dimensions received in the metadata.
	 *
	 * @return        byte array sized accordingly
	 */
	@Override
	public byte[][] allocatePicture() {
		Size size = vMeta.getSize();
		return Picture.create(size.getWidth(), size.getHeight(), vMeta.getColor()).getData();
	}
	
	/**
	 * Overridden method to return a MediaInfo instance.
	 * This method is unused.
	 *
	 * @return        Initialiized MediaInfo instance.
	 * @see           MediaInfo
	 */
	@Override
	public MediaInfo getMediaInfo() {
		return new MediaInfo(vMeta.getSize());
	}
}
