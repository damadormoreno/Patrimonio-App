import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * App icon: an italic Instrument Serif "P" in ink with a rising navy tick, on paper.
 * Writes Android vector path data (108 viewport, inside the adaptive-icon safe zone), opaque PNGs for iOS (1024) and
 * the Play listing (512), and previews of the adaptive icon under circle / squircle masks and as a themed icon.
 *
 * Usage (JDK 11+, headless): java -Djava.awt.headless=true tools/icon/AppIcon.java
 *   composeApp/src/commonMain/composeResources/font/instrument_serif_italic.ttf <out-dir>
 * Then copy p.path / tick.path into res/drawable/ic_launcher_{foreground,monochrome}.xml, ios-1024.png into the
 * iOS AppIcon set and play-512.png into docs/store/.
 */
public class AppIcon {
    static final Color PAPER = new Color(0xFBF7EE);
    static final Color INK = new Color(0x1C1814);
    static final Color NAVY = new Color(0x283B4F);

    public static void main(String[] args) throws Exception {
        File font = new File(args[0]);
        File out = new File(args[1]);
        out.mkdirs();
        Font serif = Font.createFont(Font.TRUETYPE_FONT, font).deriveFont(760f);

        // Composition in a 1024 design space.
        java.awt.font.GlyphVector gv = serif.createGlyphVector(new java.awt.font.FontRenderContext(null, true, true), "P");
        Shape p = gv.getOutline(250, 800);
        Path2D tick = new Path2D.Double();
        tick.moveTo(560, 760); tick.lineTo(680, 640); tick.lineTo(760, 700); tick.lineTo(880, 560);
        Shape tickShape = new BasicStroke(46f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(tick);
        Rectangle2D box = new Area(p).getBounds2D().createUnion(tickShape.getBounds2D());

        // Android adaptive icon: 108 viewport, composition fitted in a 58-unit box (safe zone is a 66 circle).
        AffineTransform toVector = fit(box, 108, 46);
        String pPath = pathData(toVector.createTransformedShape(p));
        String tickPath = pathData(toVector.createTransformedShape(tickShape));
        Files.writeString(new File(out, "p.path").toPath(), pPath);
        Files.writeString(new File(out, "tick.path").toPath(), tickPath);

        // Opaque store icons: composition fitted in 66% of the canvas.
        ImageIO.write(render(p, tickShape, box, 1024, 0.66, null), "png", new File(out, "ios-1024.png"));
        ImageIO.write(render(p, tickShape, box, 512, 0.66, null), "png", new File(out, "play-512.png"));

        // Previews of the adaptive icon as launchers would mask it (108 -> 432 px, mask = the 72 visible units).
        BufferedImage adaptive = render(p, tickShape, box, 432, 46.0 / 108.0, null);
        BufferedImage themed = renderThemed(p, tickShape, box, 432);
        int s = 288, gap = 40;
        BufferedImage sheet = new BufferedImage(4 * s + 5 * gap, s + 2 * gap, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        hints(g);
        g.setColor(new Color(0x3A332B));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        BufferedImage ios = ImageIO.read(new File(out, "ios-1024.png"));
        Shape[] masks = {
            new Ellipse2D.Double(0, 0, s, s),
            new RoundRectangle2D.Double(0, 0, s, s, s * 0.6, s * 0.6),
            new Ellipse2D.Double(0, 0, s, s),
            new RoundRectangle2D.Double(0, 0, s, s, s * 0.45, s * 0.45),
        };
        BufferedImage[] imgs = {adaptive, adaptive, themed, ios};
        for (int i = 0; i < 4; i++) {
            int x = gap + i * (s + gap);
            Graphics2D gi = (Graphics2D) g.create();
            gi.translate(x, gap);
            gi.setClip(masks[i]);
            if (i < 3) {
                // The visible 72/108 of the adaptive canvas.
                double crop = 432 * 18.0 / 108.0, size = 432 * 72.0 / 108.0;
                gi.drawImage(imgs[i], 0, 0, s, s, (int) crop, (int) crop, (int) (crop + size), (int) (crop + size), null);
            } else {
                gi.drawImage(imgs[i], 0, 0, s, s, null);
            }
            gi.dispose();
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(out, "preview-b.png"));
        System.out.println("ok");
    }

    static AffineTransform fit(Rectangle2D box, double canvas, double target) {
        double k = target / Math.max(box.getWidth(), box.getHeight());
        AffineTransform t = new AffineTransform();
        t.translate(canvas / 2, canvas / 2);
        t.scale(k, k);
        t.translate(-box.getCenterX(), -box.getCenterY());
        return t;
    }

    static BufferedImage render(Shape p, Shape tick, Rectangle2D box, int size, double fraction, Color bgOverride) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setColor(bgOverride != null ? bgOverride : PAPER);
        g.fillRect(0, 0, size, size);
        AffineTransform t = fit(box, size, size * fraction);
        g.setColor(INK);
        g.fill(t.createTransformedShape(p));
        g.setColor(NAVY);
        g.fill(t.createTransformedShape(tick));
        g.dispose();
        return img;
    }

    static BufferedImage renderThemed(Shape p, Shape tick, Rectangle2D box, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setColor(new Color(0xD9E4E8));
        g.fillRect(0, 0, size, size);
        AffineTransform t = fit(box, size, size * 46.0 / 108.0);
        g.setColor(new Color(0x283B4F));
        g.fill(t.createTransformedShape(p));
        g.fill(t.createTransformedShape(tick));
        g.dispose();
        return img;
    }

    static String pathData(Shape shape) {
        StringBuilder sb = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> sb.append("M").append(f(c[0])).append(",").append(f(c[1]));
                case PathIterator.SEG_LINETO -> sb.append("L").append(f(c[0])).append(",").append(f(c[1]));
                case PathIterator.SEG_QUADTO -> sb.append("Q").append(f(c[0])).append(",").append(f(c[1])).append(" ")
                    .append(f(c[2])).append(",").append(f(c[3]));
                case PathIterator.SEG_CUBICTO -> sb.append("C").append(f(c[0])).append(",").append(f(c[1])).append(" ")
                    .append(f(c[2])).append(",").append(f(c[3])).append(" ").append(f(c[4])).append(",").append(f(c[5]));
                case PathIterator.SEG_CLOSE -> sb.append("Z");
            }
        }
        return sb.toString();
    }

    static String f(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
