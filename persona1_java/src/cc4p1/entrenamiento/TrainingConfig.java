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

    public final boolean augmentationEnabled;
    public final int augmentationVariantsPerImage;
    public final double momentum;
    public final double l2Regularization;
    public final double learningRateDecay;
    public final int learningRateDecayEvery;

    // --- Nuevos: validacion y early stopping ---
    public final double validationSplit;         // fraccion de cada clase reservada para validacion (0.0 = desactivado)
    public final int validateEvery;               // cada cuantas epocas se evalua contra validacion
    public final boolean earlyStoppingEnabled;
    public final int earlyStoppingPatience;        // num. de evaluaciones de validacion sin mejora antes de parar

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

        this.augmentationEnabled = Boolean.parseBoolean(p.getProperty("augmentation.enabled", "true"));
        this.augmentationVariantsPerImage = Integer.parseInt(p.getProperty("augmentation.variants", "4"));
        this.momentum = Double.parseDouble(p.getProperty("momentum", "0.9"));
        this.l2Regularization = Double.parseDouble(p.getProperty("l2.regularization", "0.0005"));
        this.learningRateDecay = Double.parseDouble(p.getProperty("learning.rate.decay", "0.95"));
        this.learningRateDecayEvery = Integer.parseInt(p.getProperty("learning.rate.decay.every", "20"));

        this.validationSplit = Double.parseDouble(p.getProperty("validation.split", "0.15"));
        this.validateEvery = Integer.parseInt(p.getProperty("validate.every", "5"));
        this.earlyStoppingEnabled = Boolean.parseBoolean(p.getProperty("early.stopping.enabled", "true"));
        this.earlyStoppingPatience = Integer.parseInt(p.getProperty("early.stopping.patience", "10"));
    }
}