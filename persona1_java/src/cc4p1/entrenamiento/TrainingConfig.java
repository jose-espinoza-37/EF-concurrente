package cc4p1.entrenamiento;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/** Lee config/training.properties con los hiperparametros y rutas del entrenamiento. */
public class TrainingConfig {

    public final String datasetPath;
    public final int imageWidth;
    public final int imageHeight;
    public final int hiddenSize;
    public final double learningRate;
    public final int epochs;
    public final int threads;
    public final String modelOutputPath;
    public final int weightsServerPort;

    public TrainingConfig(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            p.load(in);
        }
        this.datasetPath = p.getProperty("dataset.path", "dataset");
        this.imageWidth = Integer.parseInt(p.getProperty("image.width", "32"));
        this.imageHeight = Integer.parseInt(p.getProperty("image.height", "32"));
        this.hiddenSize = Integer.parseInt(p.getProperty("hidden.size", "64"));
        this.learningRate = Double.parseDouble(p.getProperty("learning.rate", "0.05"));
        this.epochs = Integer.parseInt(p.getProperty("epochs", "200"));
        this.threads = Integer.parseInt(p.getProperty("batch.threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        this.modelOutputPath = p.getProperty("model.output.path", "modelo/pesos_modelo.bin");
        this.weightsServerPort = Integer.parseInt(p.getProperty("weights.server.port", "6100"));
    }
}
