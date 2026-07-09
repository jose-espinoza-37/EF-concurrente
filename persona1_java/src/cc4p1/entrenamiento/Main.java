package cc4p1.entrenamiento;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


public class Main {

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config/training.properties";
        TrainingConfig config = new TrainingConfig(configPath);

        System.out.println("=== Servidor de Entrenamiento de IA (Java) ===");
        System.out.println("Dataset: " + config.datasetPath);
        System.out.println("Hilos de entrenamiento: " + config.threads);
        System.out.println("Augmentation: " + config.augmentationEnabled
                + " (variantes por imagen real: " + config.augmentationVariantsPerImage + ")");
        System.out.println("Momentum: " + config.momentum + "  L2: " + config.l2Regularization
                + "  LR decay: x" + config.learningRateDecay + " cada " + config.learningRateDecayEvery + " epocas");
        System.out.println("Validacion: " + (config.validationSplit * 100) + "% de cada clase, cada "
                + config.validateEvery + " epocas. Early stopping: " + config.earlyStoppingEnabled
                + " (paciencia=" + config.earlyStoppingPatience + ")");

        ImageDataset dataset = ImageDataset.load(config.datasetPath, config.imageWidth, config.imageHeight,
                config.augmentationEnabled, config.augmentationVariantsPerImage,
                config.validationSplit, new Random(2024L));
        int n = dataset.classNames.size();
        if (n < 2) {
            throw new IllegalStateException("Se necesitan al menos 2 clases para entrenar (n>=2).");
        }

        int inputSize = config.imageWidth * config.imageHeight * 3;
        NeuralNetwork network = new NeuralNetwork(inputSize, config.hiddenSize, n, 42L);

        List<Sample> samples = new ArrayList<>(dataset.samples);
        List<List<Sample>> shards = splitIntoShards(samples, config.threads);

        ExecutorService pool = Executors.newFixedThreadPool(config.threads);
        long start = System.currentTimeMillis();

        double learningRate = config.learningRate;

        NeuralNetwork.WeightSnapshot bestSnapshot = null;
        double bestValLoss = Double.POSITIVE_INFINITY;
        int evaluacionesSinMejora = 0;
        boolean hayValidacion = !dataset.validationSamples.isEmpty();
        int epocaFinal = config.epochs;

        for (int epoch = 1; epoch <= config.epochs; epoch++) {
            Collections.shuffle(samples);
            shards = splitIntoShards(samples, config.threads);

            List<Future<NeuralNetwork.Gradients>> futures = new ArrayList<>();
            for (List<Sample> shard : shards) {
                futures.add(pool.submit(() -> network.computeGradients(shard)));
            }

            NeuralNetwork.Gradients total = new NeuralNetwork.Gradients(config.hiddenSize, inputSize, n);
            for (Future<NeuralNetwork.Gradients> f : futures) {
                total.addInPlace(f.get());
            }
            network.applyGradients(total, learningRate, config.momentum, config.l2Regularization);

            if (config.learningRateDecayEvery > 0 && epoch % config.learningRateDecayEvery == 0) {
                learningRate *= config.learningRateDecay;
            }

            boolean tocaValidar = hayValidacion
                    && (epoch % config.validateEvery == 0 || epoch == 1 || epoch == config.epochs);

            if (epoch % 10 == 0 || epoch == 1 || epoch == config.epochs || tocaValidar) {
                double avgLoss = total.lossSum / total.count;
                double acc = (100.0 * total.correct) / total.count;
                StringBuilder linea = new StringBuilder();
                linea.append(String.format("Epoca %4d/%d  train_loss=%.4f  train_acc=%.1f%%  lr=%.5f",
                        epoch, config.epochs, avgLoss, acc, learningRate));

                if (tocaValidar) {
                    NeuralNetwork.EvalResult val = network.evaluate(dataset.validationSamples);
                    linea.append(String.format("  |  val_loss=%.4f  val_acc=%.1f%%", val.avgLoss, val.accuracy));

                    if (val.avgLoss < bestValLoss) {
                        bestValLoss = val.avgLoss;
                        bestSnapshot = network.snapshot();
                        evaluacionesSinMejora = 0;
                        linea.append("  <- mejor checkpoint hasta ahora");
                    } else {
                        evaluacionesSinMejora++;
                    }
                }
                System.out.println(linea);

                if (tocaValidar && config.earlyStoppingEnabled
                        && evaluacionesSinMejora >= config.earlyStoppingPatience) {
                    System.out.println("[EarlyStopping] La validacion no mejora hace "
                            + evaluacionesSinMejora + " evaluaciones seguidas. Deteniendo en la epoca " + epoch + ".");
                    epocaFinal = epoch;
                    break;
                }
            }
        }
        pool.shutdown();

        long elapsedMs = System.currentTimeMillis() - start;
        System.out.println("Entrenamiento finalizado en " + elapsedMs + " ms (epoca final: " + epocaFinal + ").");

        NeuralNetwork modeloAGuardar = network;
        if (bestSnapshot != null) {
            modeloAGuardar = NeuralNetwork.fromSnapshot(bestSnapshot);
            System.out.println("Usando el MEJOR checkpoint de validacion (val_loss=" + bestValLoss
                    + "), no necesariamente el de la ultima epoca.");
        } else if (hayValidacion) {
            System.out.println("AVISO: no se registro ningun checkpoint de validacion, se guarda el modelo final tal cual.");
        } else {
            System.out.println("AVISO: no hubo set de validacion (dataset muy chico o validation.split=0), "
                    + "se guarda el modelo final tal cual, sin garantia de que generalice bien.");
        }

        ModelPersistence.save(modeloAGuardar, dataset.classNames, config.imageWidth, config.imageHeight,
                config.modelOutputPath);

        System.out.println("Clases entrenadas (n=" + n + "): " + dataset.classNames);
        System.out.println("Listo. Ahora puede levantar el Servidor de Pesos con run_weights_server.sh");
    }

    private static List<List<Sample>> splitIntoShards(List<Sample> samples, int shardCount) {
        List<List<Sample>> shards = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) shards.add(new ArrayList<>());
        for (int i = 0; i < samples.size(); i++) {
            shards.get(i % shardCount).add(samples.get(i));
        }
        return shards;
    }
}