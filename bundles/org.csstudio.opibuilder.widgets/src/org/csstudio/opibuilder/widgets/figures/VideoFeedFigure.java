package org.csstudio.opibuilder.widgets.figures;

import org.csstudio.opibuilder.widgets.model.VideoFeedModel;
import org.csstudio.swt.widgets.util.TextPainter;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.FigureUtilities;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.RGBA;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.SWT;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.ComponentColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;

import org.jcodec.api.JCodecException;

/**
 * The video feed figure.
 *
 * @author Sven Thoennissen - Space Applications Services
 */
public final class VideoFeedFigure extends Figure {

    protected VideoFeedModel model;
	protected Image image;
	protected ImageData imageData;
	protected String dataDescription = "Waiting for video";
	private final VideoDetailMap videoDetails = VideoDetailMap.videoDetails();
	private boolean isDetailsVisible = false;
	private long lastFrameTime = 0;
	private double currentFPS = 0.0;

	/**
	 * Dispose the resources used by this figure.
	 */
    public void dispose() {
        if (image != null && !image.isDisposed()) {
            image.dispose();
            image = null;
        }
    }

	public void setDetailsVisible(boolean isVisible) {
		this.isDetailsVisible = isVisible;
		repaint();
	}

	public void setDetail(String key, String value) {
		videoDetails.setValue(key, value);
		if (isDetailsVisible) {
			repaint();
		}
	}
	
	/**
	 * Utility method to convert AWT BufferedImage to SWT ImageData.
	 * Method extended to handle ComponentColorModel.
	 *
	 * @param bufferedImage image to be converted
	 * @return converted image data
	 * @see http://www.java2s.com/Code/Java/SWT-JFace-Eclipse/ConvertbetweenSWTImageandAWTBufferedImage.htm
	 */
	private static ImageData convertToSWT(BufferedImage bufferedImage) {
		if (bufferedImage.getColorModel() instanceof ComponentColorModel) {

			// AWTUtil (JCodec) creates BufferedImage of type BGR
			if (bufferedImage.getType() == BufferedImage.TYPE_3BYTE_BGR) {
				ComponentColorModel colorModel = (ComponentColorModel) bufferedImage.getColorModel();
				PaletteData palette = new PaletteData(0x0000ff, 0x00ff00, 0xff0000);
				int scanlinePad = bufferedImage.getWidth() * colorModel.getPixelSize()/8;
				byte[] data = ((DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData();
				ImageData imageData = new ImageData(bufferedImage.getWidth(), bufferedImage.getHeight(), colorModel.getPixelSize(), palette, scanlinePad, data);
				return imageData;
			}

		} else if (bufferedImage.getColorModel() instanceof DirectColorModel) {

			DirectColorModel colorModel = (DirectColorModel) bufferedImage.getColorModel();
			PaletteData palette = new PaletteData(colorModel.getRedMask(), colorModel.getGreenMask(), colorModel.getBlueMask());
			ImageData data = new ImageData(bufferedImage.getWidth(), bufferedImage.getHeight(), colorModel.getPixelSize(), palette);
			WritableRaster raster = bufferedImage.getRaster();
			int[] pixelArray = new int[3];
			for (int y = 0; y < data.height; y++) {
				for (int x = 0; x < data.width; x++) {
					raster.getPixel(x, y, pixelArray);
					int pixel = palette.getPixel(new RGB(pixelArray[0], pixelArray[1], pixelArray[2]));
					data.setPixel(x, y, pixel);
				}
			}
			return data;

		} else if (bufferedImage.getColorModel() instanceof IndexColorModel) {
			
			IndexColorModel colorModel = (IndexColorModel) bufferedImage.getColorModel();
			int size = colorModel.getMapSize();
			byte[] reds = new byte[size];
			byte[] greens = new byte[size];
			byte[] blues = new byte[size];
			colorModel.getReds(reds);
			colorModel.getGreens(greens);
			colorModel.getBlues(blues);
			RGB[] rgbs = new RGB[size];
			for (int i = 0; i < rgbs.length; i++) {
				rgbs[i] = new RGB(reds[i] & 0xFF, greens[i] & 0xFF, blues[i] & 0xFF);
			}
			PaletteData palette = new PaletteData(rgbs);
			ImageData data = new ImageData(bufferedImage.getWidth(), bufferedImage.getHeight(), colorModel.getPixelSize(), palette);
			data.transparentPixel = colorModel.getTransparentPixel();
			WritableRaster raster = bufferedImage.getRaster();
			int[] pixelArray = new int[1];
			for (int y = 0; y < data.height; y++) {
				for (int x = 0; x < data.width; x++) {
					raster.getPixel(x, y, pixelArray);
					data.setPixel(x, y, pixelArray[0]);
				}
			}
			return data;
		}

		return null;
	}

	/**
	 * Display the given image.
	 * If the image cannot be displayed, an error message is displayed instead.
	 *
	 * @param bufferedImage the image to be displayed
	 */
	public void setVideoData(BufferedImage bufferedImage) throws JCodecException {
		imageData = convertToSWT(bufferedImage);
		if (imageData == null) {
			image = null;
			dataDescription = "Image Error";
			throw new JCodecException("Unsupported color model");
		}
		image = new Image(Display.getCurrent(), imageData);
		
		// Update FPS
		long now = System.currentTimeMillis();
		if (now - lastFrameTime > 0)
			currentFPS = 1.0/((double)(now - lastFrameTime)*0.001);
		lastFrameTime = now;
		
		repaint();
	}

	/**
	 * Display the given text if there is currently no image.
	 *
	 * @param desc text to be displayed
	 */
	public void setDataDescription(String desc) {
		dataDescription = desc;
		repaint();
	}

	/**
	 * Overridden from Figure, paint the inner graphical area of this figure.
	 *
	 * @param gfx Graphics to paint into
	 */
    @Override
    protected void paintClientArea(Graphics gfx) {
        if (bounds.width <= 0 || bounds.height <= 0) {
            return;
        }

		long time1 = System.currentTimeMillis();

		gfx.setBackgroundColor(getBackgroundColor());
		gfx.setForegroundColor(getForegroundColor());

		if (image == null) {
			gfx.fillRectangle(bounds);
        } else {
			// Draw image
			Rectangle srcArea = new Rectangle(0, 0, imageData.width, imageData.height);
			gfx.drawImage(image, srcArea, bounds);
		}

		long renderMillis = System.currentTimeMillis() - time1;

//		gfx.pushState();

		if (dataDescription != "") {
			Dimension td = FigureUtilities.getTextExtents(dataDescription, gfx.getFont());
			gfx.fillText(dataDescription, (bounds.width - td.width)/2, 0);
		}
		
		if (image != null && isDetailsVisible) {
			// Draw text about additional infos
			videoDetails.setValue(VideoDetailMap.Render, String.format("%.3f", (double)renderMillis*0.001));
			videoDetails.setValue(VideoDetailMap.FPS, String.format("%.1f", currentFPS));
			videoDetails.draw(gfx, bounds, VideoDetailMap.Corner.LEFT_BOTTOM);
		}

//		gfx.popState();

		super.paintClientArea(gfx);
    }

    /**
     * We want to have local coordinates here.
     *
     * @return True if here should used local coordinates
     */
    @Override
    protected boolean useLocalCoordinates() {
        return true;
    }
}
