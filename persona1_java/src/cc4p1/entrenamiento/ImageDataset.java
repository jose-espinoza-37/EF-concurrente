package cc4p1.entrenamiento;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Carga el dataset desde carpetas (una por clase): dataset/persona,
 * dataset/perro, dataset/gato, dataset/carro, etc.
 *
 * Cada imagen se redimensiona a un tamaño fijo (image.width x image.height)
 * y se convierte en un vector de doubles normalizado [0,1] con los canales
 * R,G,B de cada pixel (esto es lo que consume la red neuronal).
 *
 * IMPORTANTE PARA LA EVALUACION: si una carpeta de clase no tiene imagenes
 * reales, esta clase genera automaticamente imagenes sinteticas (formas de
 * color solido + ruido) SOLO para que el pipeline de entrenamiento se pueda
 * ejecutar y probar de punta a punta sin depender de un dataset externo.
 * Para la entrega real deben reemplazar el contenido de dataset/<clase>/
 * con fotografias reales de cada clase.
 */
public class ImageDataset {

    private static final int SYNTHETIC_IMAGES_PER_CLASS = 40;

    public final List<String> classNames;
    public final List<Sample> samples = new ArrayList<>();
    public final int width;
    public final int height;

    private ImageDataset(List<String> classNames, int width, int height) {
        this.classNames = classNames;
        this.width = width;
        this.height = height;
    }

    public static ImageDataset load(String datasetRoot, int width, int height) throws IOException {
        File root = new File(datasetRoot);
        File[] classDirs = root.listFiles(File::isDirectory);
        if (classDirs == null || classDirs.length == 0) {
            throw new IOException("No se encontraron subcarpetas de clase dentro de: " + datasetRoot);
        }

        List<String> classNames = new ArrayList<>();
        for (File dir : classDirs) classNames.add(dir.getName());
        classNames.sort(String::compareTo); // orden estable y reproducible

        ImageDataset dataset = new ImageDataset(classNames, width, height);

        for (int label = 0; label < classNames.size(); label++) {
            File classDir = new File(root, classNames.get(label));
            File[] imageFiles = classDir.listFiles(ImageDataset::isImageFile);

            if (imageFiles == null || imageFiles.length == 0) {
                System.out.println("[ImageDataset] AVISO: '" + classNames.get(label)
                        + "' no tiene imagenes reales. Generando " + SYNTHETIC_IMAGES_PER_CLASS
                        + " imagenes sinteticas de prueba (reemplazar por fotos reales antes de la entrega).");
                generateSyntheticImages(classDir, label, width, height);
                imageFiles = classDir.listFiles(ImageDataset::isImageFile);
            }

            for (File imgFile : imageFiles) {
                double[] features = loadFeatures(imgFile, width, height);
                if (features != null) {
                    dataset.samples.add(new Sample(features, label));
                }
            }
        }

        System.out.println("[ImageDataset] Clases: " + classNames);
        System.out.println("[ImageDataset] Total de muestras cargadas: " + dataset.samples.size());
        return dataset;
    }

    private static boolean isImageFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
    }

    private static double[] loadFeatures(File file, int width, int height) {
        try {
            BufferedImage original = ImageIO.read(file);
            if (original == null) return null;
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, width, height, null);
            g.dispose();
            return toFeatureVector(resized, width, height);
        } catch (IOException e) {
            System.err.println("No se pudo leer la imagen " + file + ": " + e.getMessage());
            return null;
        }
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

    /** Genera imagenes sinteticas deterministas por clase, solo para poder correr el pipeline sin dataset real. */
    private static void generateSyntheticImages(File classDir, int label, int width, int height) throws IOException {
        classDir.mkdirs();
        Random rnd = new Random(1000 + label); // semilla fija por clase -> reproducible
        // Un color "base" distinto por clase para que el patron sea aprendible
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
