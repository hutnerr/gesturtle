package com.gesturtle.ui.components;

import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A square icon drawn from one of the bundled SVGs in resources/icons. The
 * file's own colors are ignored: filled paths get the "icon-shape" style
 * class and stroked (line-drawn) paths get "icon-line", so style.css colors
 * icons from the theme tokens (and per context: nav items, buttons,
 * favorites).
 *
 * Only the subset of SVG those files use is understood: a viewBox, path
 * "d" data, even-odd fill rules (on a path or a group), strokes with a
 * width, and translate()s on (possibly nested) groups.
 */
public class Icon extends Region {
    private record PathSpec(String d, boolean evenOdd, Double strokeWidth) {
    }

    private record Parsed(double minX, double minY, double width, double height,
                          List<PathSpec> paths, double translateX, double translateY) {
    }

    private static final Map<String, Parsed> CACHE = new ConcurrentHashMap<>();
    private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"([^\"]+)\"");
    private static final Pattern PATH_TAG = Pattern.compile("<path\\b[^>]*>");
    private static final Pattern D_ATTR = Pattern.compile("\\sd=\"([^\"]+)\"");
    private static final Pattern STROKE_WIDTH = Pattern.compile("stroke-width=\"([\\d.]+)\"");
    private static final Pattern GROUP_EVEN_ODD = Pattern.compile("<g\\b[^>]*fill-rule=\"evenodd\"");
    private static final Pattern TRANSLATE = Pattern.compile("translate\\(([-\\d.]+)[,\\s]+([-\\d.]+)\\)");

    public Icon(String name, double size) {
        getStyleClass().addAll("icon", "icon-" + name);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);

        Parsed svg = CACHE.computeIfAbsent(name, Icon::parse);
        Group shapes = new Group();
        for (PathSpec spec : svg.paths()) {
            SVGPath path = new SVGPath();
            path.setContent(spec.d());
            path.setFillRule(spec.evenOdd() ? FillRule.EVEN_ODD : FillRule.NON_ZERO);
            if (spec.strokeWidth() != null) {
                // In viewBox units, so it scales with the icon like the file intends.
                path.setStrokeWidth(spec.strokeWidth());
                path.setStrokeLineCap(StrokeLineCap.ROUND);
                path.setStrokeLineJoin(StrokeLineJoin.ROUND);
                path.getStyleClass().add("icon-line");
            } else {
                path.getStyleClass().add("icon-shape");
            }
            shapes.getChildren().add(path);
        }
        double scale = size / Math.max(svg.width(), svg.height());
        // Applied right to left: group translate, then move the viewBox origin to 0,0, then scale to size.
        shapes.getTransforms().addAll(new Scale(scale, scale, 0, 0),
                new Translate(-svg.minX() + (Math.max(svg.width(), svg.height()) - svg.width()) / 2,
                        -svg.minY() + (Math.max(svg.width(), svg.height()) - svg.height()) / 2),
                new Translate(svg.translateX(), svg.translateY()));
        shapes.setManaged(false);
        getChildren().add(shapes);
    }

    private static Parsed parse(String name) {
        String svg;
        try (InputStream in = Icon.class.getResourceAsStream("/icons/" + name + ".svg")) {
            if (in == null) throw new IllegalArgumentException("No icon named " + name);
            svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher vb = VIEW_BOX.matcher(svg);
        if (!vb.find()) throw new IllegalArgumentException("Icon " + name + " has no viewBox");
        String[] box = vb.group(1).trim().split("[\\s,]+");
        boolean groupEvenOdd = GROUP_EVEN_ODD.matcher(svg).find();
        List<PathSpec> paths = new ArrayList<>();
        Matcher tag = PATH_TAG.matcher(svg);
        while (tag.find()) {
            String attrs = tag.group();
            Matcher d = D_ATTR.matcher(attrs);
            if (!d.find()) continue;
            Double strokeWidth = null;
            if (attrs.contains("stroke=\"") && !attrs.contains("stroke=\"none\"")) {
                Matcher w = STROKE_WIDTH.matcher(attrs);
                strokeWidth = w.find() ? Double.parseDouble(w.group(1)) : 1.0;
            }
            paths.add(new PathSpec(d.group(1), groupEvenOdd || attrs.contains("evenodd"), strokeWidth));
        }
        // Nested groups' translates add up (the files only nest them around the paths).
        Matcher t = TRANSLATE.matcher(svg);
        double tx = 0, ty = 0;
        while (t.find()) {
            tx += Double.parseDouble(t.group(1));
            ty += Double.parseDouble(t.group(2));
        }
        return new Parsed(Double.parseDouble(box[0]), Double.parseDouble(box[1]),
                Double.parseDouble(box[2]), Double.parseDouble(box[3]),
                paths, tx, ty);
    }
}
