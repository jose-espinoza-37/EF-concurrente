package cc4p1.entrenamiento;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;


public class ImageDataset {

    private static final int SYNTHETIC_IMAGES_PER_CLASS = 40;

    public final List<String> classNames;
    /** Muestras de ENTRENAMIENTO (incluye las variantes aumentadas). */
    public final List<Sample> samples = new ArrayList<>();
    /** Muestras de VALIDACION -- nunca se usan para calcular gradientes. */
    public final List<Sample> validationSamples = new ArrayList<>();
    public final int width;
    public final int height;

    private ImageDataset(List<String> classNames, int width, int height) {
        this.classNames = classNames;
        this.width = width;
        this.height = height;
    }

    public static ImageDataset load(String datasetRoot, int width, int height) throws IOException {
        return load(datasetRoot, width, height, true, 4, 0.15, new Random(2024L));
    }

    public static ImageDataset load(String datasetRoot, int width, int height,
                                     boolean augmentationEnabled, int variantsPerImage,
                                     double validationSplit, Random rnd)
            throws IOException {
        File root = new File(datasetRoot);
        File[] classDirs = root.listFiles(File::isDirectory);
        if (classDirs == null || classDirs.length == 0) {
            throw new IOException("No se encontraron subcarpetas de clase dentro de: " + datasetRoot);
        }

        List<String> classNames = new ArrayList<>();
        for (File dir : classDirs) classNames.add(dir.getName());
        classNames.sort(String::compareTo);

        ImageDataset dataset = new ImageDataset(classNames, width, height);

        for (int label = 0; label < classNames.size(); label++) {
            File classDir = new File(root, classNames.get(label));
            File[] imageFilesArr = classDir.listFiles(ImageDataset::isImageFile);

            boolean esSintetico = (imageFilesArr == null || imageFilesArr.length == 0);
            if (esSintetico) {
                System.out.println("[ImageDataset] AVISO: '" + classNames.get(label)
                        + "' no tiene imagenes reales. Generando " + SYNTHETIC_IMAGES_PER_CLASS
                        + " imagenes sinteticas de prueba (reemplazar por fotos reales antes de la entrega).");
                generateSyntheticImages(classDir, label, width, height);
                imageFilesArr = classDir.listFiles(ImageDataset::isImageFile);
            }

            List<File> imageFiles = new ArrayList<>(Arrays.asList(imageFilesArr));
            java.util.Collections.shuffle(imageFiles, rnd); // orden aleatorio pero reproducible (semilla fija)

            int total = imageFiles.size();
            int valCount = 0;
            if (validationSplit > 0.0 && total >= 5) {
                valCount = (int) Math.round(total * validationSplit);
                valCount = Math.max(1, Math.min(valCount, total - 1)); // al menos 1 en train y 1 en val
            }

            int cargadasTrain = 0;
            int cargadasVal = 0;
            int cargadasAumentadas = 0;

            for (int i = 0; i < total; i++) {
                File imgFile = imageFiles.get(i);
                BufferedImage resized = loadAndResize(imgFile, width, height);
                if (resized == null) continue;

                boolean esValidacion = i < valCount;

                if (esValidacion) {
                    dataset.validationSamples.add(new Sample(toFeatureVector(resized, width, height), label));
                    cargadasVal++;
                } else {
                    dataset.samples.add(new Sample(toFeatureVector(resized, width, height), label));
                    cargadasTrain++;

                    if (augmentationEnabled && !esSintetico) {
                        for (int v = 0; v < variantsPerImage; v++) {
                            BufferedImage variante = augment(resized, width, height, rnd);
                            dataset.samples.add(new Sample(toFeatureVector(variante, width, height), label));
                            cargadasAumentadas++;
                        }
                    }
                }
            }
            System.out.println("[ImageDataset] Clase '" + classNames.get(label) + "': "
                    + cargadasTrain + " train (originales)"
                    + (augmentationEnabled && !esSintetico ? " + " + cargadasAumentadas + " aumentadas" : "")
                    + "  |  " + cargadasVal + " validacion");
        }

        System.out.println("[ImageDataset] Clases: " + classNames);
        System.out.println("[ImageDataset] Total TRAIN: " + dataset.samples.size()
                + "   Total VALIDACION: " + dataset.validationSamples.size());
        return dataset;
    }

    private static boolean isImageFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
    }

    private static BufferedImage loadAndResize(File file, int width, int height) {
        try {
            BufferedImage original = ImageIO.read(file);
            if (original == null) return null;
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, width, height, null);
            g.dispose();
            return resized;
        } catch (IOException e) {
            System.err.println("No se pudo leer la imagen " + file + ": " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Data augmentation
    // ------------------------------------------------------------------

    private static BufferedImage augment(BufferedImage src, int width, int height, Random rnd) {
        BufferedImage result = src;

        if (rnd.nextBoolean()) {
            result = flipHorizontal(result);
        }

        double zoom = 0.80 + rnd.nextDouble() * 0.20;
        int cropW = Math.max(1, (int) (width * zoom));
        int cropH = Math.max(1, (int) (height * zoom));
        int maxX = Math.max(0, width - cropW);
        int maxY = Math.max(0, height - cropH);
        int x = maxX > 0 ? rnd.nextInt(maxX + 1) : 0;
        int y = maxY > 0 ? rnd.nextInt(maxY + 1) : 0;
        result = cropAndRescale(result, x, y, cropW, cropH, width, height);

        double brightness = 0.75 + rnd.nextDouble() * 0.50;
        result = adjustBrightness(result, brightness);

        return result;
    }

    private static BufferedImage flipHorizontal(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                out.setRGB(w - 1 - xx, yy, src.getRGB(xx, yy));
            }
        }
        return out;
    }

    private static BufferedImage cropAndRescale(BufferedImage src, int x, int y, int cropW, int cropH,
                                                 int outW, int outH) {
        BufferedImage cropped = src.getSubimage(x, y, cropW, cropH);
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cropped, 0, 0, outW, outH, null);
        g.dispose();
        return out;
    }

    private static BufferedImage adjustBrightness(BufferedImage src, double factor) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int rgb = src.getRGB(xx, yy);
                int r = clamp((int) (((rgb >> 16) & 0xFF) * factor));
                int g2 = clamp((int) (((rgb >> 8) & 0xFF) * factor));
                int b = clamp((int) ((rgb & 0xFF) * factor));
                out.setRGB(xx, yy, (r << 16) | (g2 << 8) | b);
            }
        }
        return out;
    }

    private static double[] toFeatureVector(BufferedImage img, int width, int height) {
        double[] features = new double[width * height * 3];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int gr = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                features[idx++] = r / 255.0;
                features[idx++] = gr / 255.0;
                features[idx++] = b / 255.0;
            }
        }
        return features;
    }

    private static void generateSyntheticImages(File classDir, int label, int width, int height) throws IOException {
        classDir.mkdirs();
        Random rnd = new Random(1000 + label);
        int baseR = (label * 71) % 256;
        int baseG = (label * 131) % 256;
        int baseB = (label * 193) % 256;

        for (int i = 0; i < SYNTHETIC_IMAGES_PER_CLASS; i++) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int noise = rnd.nextInt(40) - 20;
                    int r = clamp(baseR + noise);
                    int g = clamp(baseG + noise);
                    int b = clamp(baseB + noise);
                    img.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            File out = new File(classDir, "synthetic_" + i + ".png");
            ImageIO.write(img, "png", out);
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}