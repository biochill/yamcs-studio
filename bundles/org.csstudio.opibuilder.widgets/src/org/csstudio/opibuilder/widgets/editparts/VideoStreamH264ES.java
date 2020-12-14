package org.csstudio.opibuilder.widgets.editparts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.BufferOverflowException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.jcodec.api.NotSupportedException;
import org.jcodec.codecs.h264.decode.SliceHeaderReader;
import org.jcodec.codecs.h264.io.model.NALUnit;
import org.jcodec.codecs.h264.io.model.NALUnitType;
import org.jcodec.codecs.h264.io.model.PictureParameterSet;
import org.jcodec.codecs.h264.io.model.RefPicMarking;
import org.jcodec.codecs.h264.io.model.RefPicMarking.InstrType;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.SliceHeader;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.codecs.h264.io.model.VUIParameters;
import org.jcodec.common.Demuxer;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.IntObjectMap;
import org.jcodec.common.io.BitReader;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Packet.FrameType;

/**
 * Copied and modified from BufferH264ES.java (JCodec).
 * Most of this class is copied from JCodec and not modified. The method nextFrame() was heavily modified.
 *
 * Its purpose is to accumulate a buffer of binary H.264 video data and to parse it for frames.
 * Important new methods are injectChunk(), nextFrame(), packetFromCurrentData().
 *
 * @author Sven Thoennissen - Space Applications Services
 */
public class VideoStreamH264ES implements DemuxerTrack, Demuxer {
	
	public boolean ignoreBFrames = true; // true = ignore all B frames in the stream

    private ByteBuffer videoBuffer;
	int lastPacketMark;
	NALUnit prevNu = null;
	SliceHeader prevSh = null;
    private IntObjectMap<PictureParameterSet> pps;
    private IntObjectMap<SeqParameterSet> sps;

    // POC and framenum detection
    private int prevFrameNumOffset;
    private int prevFrameNum;
    private int prevPicOrderCntMsb;
    private int prevPicOrderCntLsb;
    private int frameNo;

	public double fps;

	/**
	 * Constructor
	 */
    public VideoStreamH264ES() {
        this.pps = new IntObjectMap<PictureParameterSet>();
        this.sps = new IntObjectMap<SeqParameterSet>();

		videoBuffer = ByteBuffer.allocate(128*1024);
		videoBuffer.limit(0);
		lastPacketMark = -1;
        this.frameNo = 0;
		fps = 0;
    }

	/**
	 * Forget collected video data and reset state.
	 * This method is called when irreparable problems occur while working on received video data.
	 * It resets the video data buffer and internal state variables.
	 */
	void resetBuffer() {
		videoBuffer.limit(0);
		lastPacketMark = -1;
		prevNu = null;
		prevSh = null;
	}
	
	/**
	 * Forget collected SPS and PPS infos.
	 * Currently unused.
	 */
	void resetSpsPps() {
		pps.clear();
		sps.clear();
	}

	/**
	 * Append a chunk of data to the internal video data buffer.
	 * The internal buffer's position remains unchanged. The internal buffer's limit points at the end of the appended data.
	 *
	 * @param bb binary video stream data
	 */
	void injectChunk(ByteBuffer bb) throws BufferOverflowException {
		// Append buffer and leave position unchanged
		int mark = videoBuffer.position();
		videoBuffer.position(videoBuffer.limit());
		videoBuffer.limit(videoBuffer.capacity());
		videoBuffer.put(bb);
		videoBuffer.limit(videoBuffer.position());
		videoBuffer.position(mark);
	}

	/**
	 * Returns the next valid H.264 frame.
	 * This method is borrowed and modified from JCodec's BufferH264ES class.
	 * It searches for certain NAL units, such as SPS, PPS, IDR and non-IDR.
	 * <p>
	 * The ivar videoBuffer accumulates video data via the injectChunk() method.
	 * This method parses one NAL unit at a time, and the videoBuffer position is advanced across method calls.
	 * The ivar lastPacketMark is the starting position of the frame that is being parsed. Once the frame is complete, it is returned.
	 * <p>
	 * A packet consists of a number of NAL units. It contains one IDR or one non-IDR unit, and optionally SPS/PPS units.
	 *
	 * @return A frame, or null if no frame was detected.
	 * @see injectChunk()
	 * @see packetFromCurrentData()
	 */
    @Override
    public Packet nextFrame() {
		// At the end of this method, bb is positioned after the last valid NALU
		
//		System.out.println("Searching for frame @" + videoBuffer.position() + "-" + videoBuffer.limit());

        while (true) {
			int nalPos = videoBuffer.position();
            ByteBuffer buf = nextNALUnit(videoBuffer);
			if (buf == null) {
				System.out.println("  No more NALU @" + nalPos);
				break;
			}

			// First NAL unit
			if (lastPacketMark == -1) {
				lastPacketMark = nalPos;
			}
			
			NALUnit nu = NALUnit.read(buf);
//			System.out.println("  NALU @" + nalPos + "," + buf.limit() + " " + nu.type.getName());

            if (nu.type == NALUnitType.IDR_SLICE || nu.type == NALUnitType.NON_IDR_SLICE) {
                SliceHeader sh = readSliceHeader(buf, nu);

				// Slice header not available? This means there is no current PPS, probably due to packet loss.
				// -> flush buffer and keep reading NALUs.
				if (sh == null) {
//					System.out.println(String.format("  Found Slice @%d, discarding (no PPS) and flushing buffer", nalPos));
					ByteBuffer newBuf = ByteBuffer.allocate(videoBuffer.capacity());
					newBuf.put(videoBuffer);
					newBuf.flip();
					videoBuffer = newBuf;
					lastPacketMark = -1;
					continue;
				}
				
//				System.out.println(String.format("  Found Slice @%d, frameNum=%d type=%s ppsid=%d pocType=%d nal_ref_idc=%d", nalPos, sh.frameNum, sh.sliceType, sh.picParameterSetId, sh.sps.picOrderCntType, nu.nal_ref_idc));

				if (prevNu != null && prevSh != null && !sameFrame(prevNu, nu, prevSh, sh)) {

					videoBuffer.position(nalPos); // go to beginning of new slice, is not part of new packet
					
					return packetFromCurrentData();
                }
				
//				System.out.println("  Found Slice @" + nalPos);
				
                prevSh = sh;
                prevNu = nu;
            } else if (nu.type == NALUnitType.PPS) {
				// Remember PPS
                PictureParameterSet read = PictureParameterSet.read(buf);
                pps.put(read.picParameterSetId, read);
			} else if (nu.type == NALUnitType.SPS) {
				// Remember SPS
                SeqParameterSet read = SeqParameterSet.read(buf);
                sps.put(read.seqParameterSetId, read);
				
				VUIParameters vui = read.vuiParams;
				if (vui != null) {
					if (vui.timingInfoPresentFlag) {
						if (vui.fixedFrameRateFlag) {
							fps = (double)(vui.timeScale) / (double)(2 * vui.numUnitsInTick);
//							System.out.println(String.format("    fps=%.3f timeScale=%d units=%d fixedFrameRate=%b", fps, vui.timeScale, vui.numUnitsInTick, vui.fixedFrameRateFlag));
						} else {
							fps = 1000000.0 / ((double)(vui.timeScale) / (double)vui.numUnitsInTick);
//							System.out.println(String.format("    fps=%.0f (%.3f) timeScale=%d units=%d fixedFrameRate=%b", fps, fps, vui.timeScale, vui.numUnitsInTick, vui.fixedFrameRateFlag));
						}
					}
				}

			} else if (nu.type == NALUnitType.ACC_UNIT_DELIM) {
				// AUD units usually appear before SPS/PPS units.
				// We use it in case we have started parsing mid-stream.
				// No SPS or PPS just yet -> forget the  skip previous AUD and wait for next SPS/PPS to come.
				if (sps.size() == 0 || pps.size() == 0) {
					lastPacketMark = nalPos;
				}
            }
        }

		return null;
    }

	/**
	 * Returns the current valid H.264 frame.
	 *
	 * @return A frame packet, or null if no frame was detected.
	 */
	Packet packetFromCurrentData() {
		if (prevNu == null || prevSh == null || lastPacketMark == -1)
			return null;
		
		ByteBuffer result = videoBuffer.duplicate();
		result.position(lastPacketMark);
		result.limit(videoBuffer.position());
		Packet p = detectPoc(result, prevNu, prevSh);
		if (p != null) {
			// Have packet, compact video buffer
			ByteBuffer newBuf = ByteBuffer.allocate(videoBuffer.capacity());
			newBuf.put(videoBuffer);
			newBuf.flip();
			videoBuffer = newBuf;
			lastPacketMark = -1;
			System.out.println(String.format("  Creating packet, pts=%d ts=%d do=%d, new internal buffer @%d-%d", p.getPts(), p.getTimescale(), p.getDisplayOrder(), videoBuffer.position(), videoBuffer.limit()));
			
			prevSh = null;
			prevNu = null;
		}
		return p;
	}
	
	// MARK: - Copied from BufferH264ES, with subtle modifications for robustness and debugging

	/**
	 * Copied from BufferH264ES, added some checks.
	 */
    private SliceHeader readSliceHeader(ByteBuffer buf, NALUnit nu) {
        BitReader br = BitReader.createBitReader(buf);
        SliceHeader sh = SliceHeaderReader.readPart1(br);
        PictureParameterSet pp = pps.get(sh.picParameterSetId);
		if (sh == null || pp == null)
			return null;
        SliceHeaderReader.readPart2(sh, nu, sps.get(pp.seqParameterSetId), pp, br);
        return sh;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private boolean sameFrame(NALUnit nu1, NALUnit nu2, SliceHeader sh1, SliceHeader sh2) {
        if (sh1.picParameterSetId != sh2.picParameterSetId)
            return false;

        if (sh1.frameNum != sh2.frameNum)
            return false;

        SeqParameterSet sps = sh1.sps;

        if ((sps.picOrderCntType == 0 && sh1.picOrderCntLsb != sh2.picOrderCntLsb))
            return false;

        if ((sps.picOrderCntType == 1 && (sh1.deltaPicOrderCnt[0] != sh2.deltaPicOrderCnt[0] || sh1.deltaPicOrderCnt[1] != sh2.deltaPicOrderCnt[1])))
            return false;

        if (((nu1.nal_ref_idc == 0 || nu2.nal_ref_idc == 0) && nu1.nal_ref_idc != nu2.nal_ref_idc))
            return false;

        if (((nu1.type == NALUnitType.IDR_SLICE) != (nu2.type == NALUnitType.IDR_SLICE)))
            return false;

        if (sh1.idrPicId != sh2.idrPicId)
            return false;

        return true;
    }

	/**
	 * Copied from BufferH264ES.
	 * Fixed reseting POC Lsb/Msb.
	 */
    private Packet detectPoc(ByteBuffer result, NALUnit nu, SliceHeader sh) {
        int maxFrameNum = 1 << (sh.sps.log2MaxFrameNumMinus4 + 4);
        if (detectGap(sh, maxFrameNum)) {
            issueNonExistingPic(sh, maxFrameNum);
        }
        int absFrameNum = updateFrameNumber(sh.frameNum, maxFrameNum, detectMMCO5(sh.refPicMarkingNonIDR));

        int poc = 0;
        if (nu.type == NALUnitType.NON_IDR_SLICE) {
            poc = calcPoc(absFrameNum, nu, sh);
		} else {
			prevPicOrderCntMsb = 0;
			prevPicOrderCntLsb = 0;
        }
        return new Packet(result, absFrameNum, 1, 1, frameNo++, nu.type == NALUnitType.IDR_SLICE ? FrameType.KEY : FrameType.INTER, null, poc);
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private int updateFrameNumber(int frameNo, int maxFrameNum, boolean mmco5) {
        int frameNumOffset;
        if (prevFrameNum > frameNo)
            frameNumOffset = prevFrameNumOffset + maxFrameNum;
        else
            frameNumOffset = prevFrameNumOffset;

        int absFrameNum = frameNumOffset + frameNo;

        prevFrameNum = mmco5 ? 0 : frameNo;
        prevFrameNumOffset = frameNumOffset;
        return absFrameNum;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private void issueNonExistingPic(SliceHeader sh, int maxFrameNum) {
        int nextFrameNum = (prevFrameNum + 1) % maxFrameNum;
        // refPictureManager.addNonExisting(nextFrameNum);
        prevFrameNum = nextFrameNum;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private boolean detectGap(SliceHeader sh, int maxFrameNum) {
        return sh.frameNum != prevFrameNum && sh.frameNum != ((prevFrameNum + 1) % maxFrameNum);
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private int calcPoc(int absFrameNum, NALUnit nu, SliceHeader sh) {
        if (sh.sps.picOrderCntType == 0) {
            return calcPOC0(nu, sh);
        } else if (sh.sps.picOrderCntType == 1) {
            return calcPOC1(absFrameNum, nu, sh);
        } else {
            return calcPOC2(absFrameNum, nu, sh);
        }
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private int calcPOC2(int absFrameNum, NALUnit nu, SliceHeader sh) {
        if (nu.nal_ref_idc == 0)
            return 2 * absFrameNum - 1;
        else
            return 2 * absFrameNum;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private int calcPOC1(int absFrameNum, NALUnit nu, SliceHeader sh) {
        if (sh.sps.numRefFramesInPicOrderCntCycle == 0)
            absFrameNum = 0;
        if (nu.nal_ref_idc == 0 && absFrameNum > 0)
            absFrameNum = absFrameNum - 1;

        int expectedDeltaPerPicOrderCntCycle = 0;
        for (int i = 0; i < sh.sps.numRefFramesInPicOrderCntCycle; i++)
            expectedDeltaPerPicOrderCntCycle += sh.sps.offsetForRefFrame[i];

        int expectedPicOrderCnt;
        if (absFrameNum > 0) {
            int picOrderCntCycleCnt = (absFrameNum - 1) / sh.sps.numRefFramesInPicOrderCntCycle;
            int frameNumInPicOrderCntCycle = (absFrameNum - 1) % sh.sps.numRefFramesInPicOrderCntCycle;

            expectedPicOrderCnt = picOrderCntCycleCnt * expectedDeltaPerPicOrderCntCycle;
            for (int i = 0; i <= frameNumInPicOrderCntCycle; i++)
                expectedPicOrderCnt = expectedPicOrderCnt + sh.sps.offsetForRefFrame[i];
        } else {
            expectedPicOrderCnt = 0;
        }
        if (nu.nal_ref_idc == 0)
            expectedPicOrderCnt = expectedPicOrderCnt + sh.sps.offsetForNonRefPic;

        return expectedPicOrderCnt + sh.deltaPicOrderCnt[0];
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private int calcPOC0(NALUnit nu, SliceHeader sh) {
        int pocCntLsb = sh.picOrderCntLsb;
        int maxPicOrderCntLsb = 1 << (sh.sps.log2MaxPicOrderCntLsbMinus4 + 4);

        // TODO prevPicOrderCntMsb should be wrapped!!
        int picOrderCntMsb;
        if ((pocCntLsb < prevPicOrderCntLsb) && ((prevPicOrderCntLsb - pocCntLsb) >= (maxPicOrderCntLsb / 2)))
            picOrderCntMsb = prevPicOrderCntMsb + maxPicOrderCntLsb;
        else if ((pocCntLsb > prevPicOrderCntLsb) && ((pocCntLsb - prevPicOrderCntLsb) > (maxPicOrderCntLsb / 2)))
            picOrderCntMsb = prevPicOrderCntMsb - maxPicOrderCntLsb;
        else
            picOrderCntMsb = prevPicOrderCntMsb;

        if (nu.nal_ref_idc != 0) {
            prevPicOrderCntMsb = picOrderCntMsb;
            prevPicOrderCntLsb = pocCntLsb;
        }

        return picOrderCntMsb + pocCntLsb;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    private boolean detectMMCO5(RefPicMarking refPicMarkingNonIDR) {
        if (refPicMarkingNonIDR == null)
            return false;

        RefPicMarking.Instruction[] instructions = refPicMarkingNonIDR.getInstructions();
        for (int i = 0; i < instructions.length; i++) {
            RefPicMarking.Instruction instr = instructions[i];
            if (instr.getType() == InstrType.CLEAR) {
                return true;
            }
        }
        return false;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    public SeqParameterSet[] getSps() {
        return sps.values(new SeqParameterSet[0]);
    }

	/**
	 * Copied from BufferH264ES.
	 */
    public PictureParameterSet[] getPps() {
        return pps.values(new PictureParameterSet[0]);
    }

	/**
	 * Copied from BufferH264ES.
	 */
    @Override
    public DemuxerTrackMeta getMeta() {
        return null;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    @Override
    public void close() throws IOException {
        // TODO Auto-generated method stub
        
    }

	/**
	 * Copied from BufferH264ES.
	 */
    @Override
    public List<? extends DemuxerTrack> getTracks() {
        return getVideoTracks();
    }

	/**
	 * Copied from BufferH264ES.
	 */
    @Override
    public List<? extends DemuxerTrack> getVideoTracks() {
        List<DemuxerTrack> tracks = new ArrayList<DemuxerTrack>();
        tracks.add(this);
        return tracks;
    }

	/**
	 * Copied from BufferH264ES.
	 */
    @Override
    public List<? extends DemuxerTrack> getAudioTracks() {
        List<DemuxerTrack> tracks = new ArrayList<DemuxerTrack>();
        return tracks;
    }

	// MARK: - Copied from H264Utils
	
	/**
	 * Copied and modified from H264Utils.java (JCodec).
	 * The original method parses until the end of the buffer and always returns a buffer.
	 * This method returns null if the NAL unit is incomplete, or a buffer with a complete NAL unit.
	 *
	 * @param buf buffer to search for the next NAL unit
	 * @return a complete NAL unit buffer or null if no buffer was found
	 */
	ByteBuffer nextNALUnit(ByteBuffer buf) {
		// If there is a valid NAL, position buf behind it. Otherwise stay at this (incomplete) NAL.
		int from = buf.position();
		skipToNALUnit(buf);
		ByteBuffer result = buf.hasArray() ? gotoNALUnit(buf) : gotoNALUnitWithArray(buf);
		if (result == null)
			buf.position(from); // undo all get's
		return result;
	}
	
	/**
	 * Copied from H264Utils.
	 */
	void skipToNALUnit(ByteBuffer buf) {
		int val = 0xffffffff;
		while (buf.hasRemaining()) {
			val <<= 8;
			val |= (buf.get() & 0xff);
			if ((val & 0xffffff) == 1) {
				break;
			}
		}
	}
	
	/**
	 * Copied and modified from H264Utils.
	 */
	ByteBuffer gotoNALUnit(ByteBuffer buf) {
		if (!buf.hasRemaining())
			return null;
		
		int from = buf.position();
		ByteBuffer result = buf.slice();
		result.order(ByteOrder.BIG_ENDIAN);
		
		int val = 0xffffffff;
		while (buf.hasRemaining()) {
			val <<= 8;
			val |= (buf.get() & 0xff);
			if ((val & 0xffffff) == 1) {
				// Found next NAL unit; return current unit
				buf.position(buf.position() - (val == 1 ? 4 : 3));
				result.limit(buf.position() - from);
				return result;
			}
		}
		// No next marker found -> rewind
		buf.position(from);
		return null;
	}

	/**
	 * Copied and modified from H264Utils.
	 */
	ByteBuffer gotoNALUnitWithArray(ByteBuffer buf) {
		if (!buf.hasRemaining())
			return null;
		
		int from = buf.position();
		ByteBuffer result = buf.slice();
		result.order(ByteOrder.BIG_ENDIAN);
		
		byte[] arr = buf.array();
		int pos = from + buf.arrayOffset();
		int posFrom = pos;
		int lim = buf.limit() + buf.arrayOffset();
		
		while (pos < lim) {
			byte b = arr[pos];
			
			if ((b & 254) == 0) {
				while (b == 0 && ++pos < lim)
					b = arr[pos];
				
				if (b == 1) {
					if (pos - posFrom >= 2 && arr[pos - 1] == 0 && arr[pos - 2] == 0) {
						int lenSize = (pos - posFrom >= 3 && arr[pos - 3] == 0) ? 4 : 3;
						
						// point buf to NAL marker
						buf.position(pos + 1 - buf.arrayOffset() - lenSize);
						result.limit(buf.position() - from);
						return result;
					}
				}
			}
			
			pos += 3;
		}
		
		// Next NAL not found, go back to beginning of incomplete NAL
		buf.position(from);
		return null;
	}
	
}
