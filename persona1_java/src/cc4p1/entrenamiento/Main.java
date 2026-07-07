package cc4p1.entrenamiento;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Servidor de Entrenamiento de IA (Persona 1 - Java).
 *
 * Uso:
 *   java cc4p1.entrenamiento.Main config/training.properties
 *
 * Logica de alto nivel:
 *   1. Carga el dataset de imagenes (una carpeta por clase).
 *   2. Reparte las muestras de entrenamiento en "threads" shards.
 *   3. En cada epoca, lanza un hilo por shard para calcular gradientes en
 *      PARALELO (cada hilo solo lee los pesos actuales, no los modifica).
 *   4. El hilo principal suma los gradientes de todos los shards y aplica
 *      UNA sola actualizacion de pesos por epoca (descenso de gradiente
 *      "data-parallel"). Esto demuestra el procesamiento concurrente y
 *      distribuido de la carga de entrenamiento que pide el enunciado.
 *   5. Al terminar, persiste los pesos entrenados en disco para que el
 *      Servidor de Testeo (Python) los consuma.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config/training.properties";
        TrainingConfig config = new TrainingConfig(configPath);

        System.out.println("=== Servidor de Entrenamiento de IA (Java) ===");
        System.out.println("Dataset: " + config.datasetPath);
        System.out.println("Hilos de entrenamiento: " + config.threads);

        ImageDataset dataset = ImageDataset.load(config.datasetPath, config.imageWidth, config.imageHeight);
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

        for (int epoch = 1; epoch <= config.epochs; epoch++) {
            Collections.shuffle(samples); // orden distinto cada epoca
            shards = splitIntoShards(samples, config.threads);

            List<Future<NeuralNetwork.Gradients>> futures = new ArrayList<>();
            for (List<Sample> shard : shards) {
                futures.add(pool.submit(() -> network.computeGradients(shard)));
            }

            NeuralNetwork.Gradients total = new NeuralNetwork.Gradients(config.hiddenSize, inputSize, n);
            for (Future<NeuralNetwork.Gradients> f : futures) {
                total.addInPlace(f.get());
            }
            network.applyGradients(total, config.learningRate);

            if (epoch % 10 == 0 || epoch == 1 || epoch == config.epochs) {
                double avgLoss = total.lossSum / total.count;
                double acc = (100.0 * total.correct) / total.count;
                System.out.printf("Epoca %4d/%d  loss=%.4f  accuracy=%.1f%%%n",
                        epoch, config.epochs, avgLoss, acc);
            }
        }
        pool.shutdown();

        long elapsedMs = System.currentTimeMillis() - start;
        System.out.println("Entrenamiento finalizado en " + elapsedMs + " ms.");

        ModelPersistence.save(network, dataset.classNames, config.imageWidth, config.imageHeight,
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
