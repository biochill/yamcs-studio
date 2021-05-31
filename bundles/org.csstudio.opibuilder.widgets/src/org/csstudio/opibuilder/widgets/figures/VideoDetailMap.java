package org.csstudio.opibuilder.widgets.figures;

import java.util.Map;
import java.util.HashMap;

import org.eclipse.draw2d.FigureUtilities;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;

import org.csstudio.swt.widgets.util.TextPainter;

public class VideoDetailMap {

	public final static String RESOLUTION = "Resolution";
	public final static String COLORSPACE = "Colorspace";
	public final static String FPS = "FPS";
	public final static String PACKETNO = "Packet";
	public final static String FRAMENO = "Frame";
	public final static String RENDER = "Render";
	public final static String DECODE = "Decode";
	
	enum Corner { LEFT_TOP, LEFT_BOTTOM, RIGHT_TOP, RIGHT_BOTTOM }
	
	private Map<String, String> map;
	private String[] keyOrder;
	
	public static VideoDetailMap videoDetails() {
		String[] order = { Resolution, ColorSpace, FPS, PacketNo, FrameNo, Render, Decode };
		HashMap<String, String> map = new HashMap<>();
		for (String key : order)
			map.put(key, "-");
		VideoDetailMap details = new VideoDetailMap(map);
		details.order(order);
		return details;
	}

	public VideoDetailMap(Map<String, String> details) {
		map = new HashMap<>(details);
		keyOrder = details.keySet().toArray(String[]::new);
	}
	
	public void setValue(String key, String value) {
		map.put(key, value);
	}

	public void resetValue(String key) {
		map.put(key, "-");
	}
	
	public void order(String[] keys) {
		keyOrder = keys;
	}
	
	public void draw(Graphics gfx, Rectangle bounds, Corner corner) {

		Rectangle rect = getBoundingRectangle(gfx);
		switch (corner) {
		case LEFT_TOP:
			rect.x = bounds.x;
			rect.y = bounds.y;
			break;
		case LEFT_BOTTOM:
			rect.x = bounds.x;
			rect.y = bounds.y + bounds.height - rect.height;
			break;
		case RIGHT_TOP:
			rect.x = bounds.x + bounds.width - rect.width;
			rect.y = bounds.y;
			break;
		case RIGHT_BOTTOM:
			rect.x = bounds.x + bounds.width - rect.width;
			rect.y = bounds.y + bounds.height - rect.height;
			break;
		}

		gfx.fillRectangle(rect);
		
		int y = rect.y;
		for (String key : keyOrder) {
			String value = map.get(key);
			if (value == null)
				continue;

			TextPainter.drawText(gfx, key + ":", rect.x, y, TextPainter.TOP_LEFT);
			TextPainter.drawText(gfx, value, rect.x + rect.width, y, TextPainter.TOP_RIGHT);

			String text = key + ": " + value;
			Dimension td = FigureUtilities.getTextExtents(text, gfx.getFont());
			y += td.height;
		}
	}
	
	private Rectangle getBoundingRectangle(Graphics gfx) {
		Rectangle rect = new Rectangle();
		for (Map.Entry<String, String> entry : map.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			String text = key + ": " + value;
			Dimension td = FigureUtilities.getTextExtents(text, gfx.getFont());
			rect.width = Math.max(rect.width, td.width);
			rect.height += td.height;
		}
		return rect;
	}
}
