package cc4p1.entrenamiento;

import java.util.List;
import java.util.Random;

/**
 * Perceptron multicapa (1 capa oculta) implementado desde cero, usando
 * unicamente arrays de Java (sin librerias externas de ML), tal como pide
 * el enunciado ("usar como base las librerias que vienen con el lenguaje").
 *
 * Arquitectura:
 *   entrada (pixeles normalizados) -> oculta (sigmoide) -> salida (softmax)
 *
 * El calculo de gradientes de un lote (computeGradients) es una operacion
 * de SOLO LECTURA sobre los pesos actuales: no modifica el estado de la
 * red. Esto permite que varios hilos calculen gradientes de distintos
 * "shards" del dataset EN PARALELO sin necesidad de sincronizacion, y que
 * el hilo principal sume esos gradientes y aplique la actualizacion una
 * sola vez por epoca (descenso de gradiente por lotes, paralelizado
 * "data-parallel").
 */
public class NeuralNetwork {

    public final int inputSize;
    public final int hiddenSize;
    public final int outputSize;

    private final double[][] weightsInputHidden;  // [hidden][input]
    private final double[] biasHidden;             // [hidden]
    private final double[][] weightsHiddenOutput;  // [output][hidden]
    private final double[] biasOutput;             // [output]

    public NeuralNetwork(int inputSize, int hiddenSize, int outputSize, long seed) {
        this.inputSize = inputSize;
        this.hiddenSize = hiddenSize;
        this.outputSize = outputSize;
        Random rnd = new Random(seed);

        this.weightsInputHidden = new double[hiddenSize][inputSize];
        this.biasHidden = new double[hiddenSize];
        this.weightsHiddenOutput = new double[outputSize][hiddenSize];
        this.biasOutput = new double[outputSize];

        double scaleIH = Math.sqrt(2.0 / inputSize);
        double scaleHO = Math.sqrt(2.0 / hiddenSize);
        for (int j = 0; j < hiddenSize; j++) {
            for (int i = 0; i < inputSize; i++) {
                weightsInputHidden[j][i] = rnd.nextGaussian() * scaleIH;
            }
        }
        for (int k = 0; k < outputSize; k++) {
            for (int j = 0; j < hiddenSize; j++) {
                weightsHiddenOutput[k][j] = rnd.nextGaussian() * scaleHO;
            }
        }
    }

    /** Constructor usado al cargar un modelo ya entrenado desde disco. */
    public NeuralNetwork(double[][] wih, double[] bh, double[][] who, double[] bo) {
        this.hiddenSize = wih.length;
        this.inputSize = wih[0].length;
        this.outputSize = who.length;
        this.weightsInputHidden = wih;
        this.biasHidden = bh;
        this.weightsHiddenOutput = who;
        this.biasOutput = bo;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double[] computeHidden(double[] input) {
        double[] hidden = new double[hiddenSize];
        for (int j = 0; j < hiddenSize; j++) {
            double sum = biasHidden[j];
            double[] row = weightsInputHidden[j];
            for (int i = 0; i < inputSize; i++) {
                sum += row[i] * input[i];
            }
            hidden[j] = sigmoid(sum);
        }
        return hidden;
    }

    private double[] computeOutput(double[] hidden) {
        double[] logits = new double[outputSize];
        for (int k = 0; k < outputSize; k++) {
            double sum = biasOutput[k];
            double[] row = weightsHiddenOutput[k];
            for (int j = 0; j < hiddenSize; j++) {
                sum += row[j] * hidden[j];
            }
            logits[k] = sum;
        }
        return softmax(logits);
    }

    private static double[] softmax(double[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : logits) max = Math.max(max, v);
        double sum = 0;
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    /** Prediccion: vector de probabilidades por clase (softmax). */
    public double[] predict(double[] input) {
        return computeOutput(computeHidden(input));
    }

    public int predictClassIndex(double[] input) {
        double[] probs = predict(input);
        int best = 0;
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > probs[best]) best = i;
        }
        return best;
    }

    /** Acumulador de gradientes de un lote/shard, mas metricas de esa pasada. */
    public static class Gradients {
        public final double[][] dWih;
        public final double[] dBh;
        public final double[][] dWho;
        public final double[] dBo;
        public int count = 0;
        public double lossSum = 0;
        public int correct = 0;

        public Gradients(int hiddenSize, int inputSize, int outputSize) {
            dWih = new double[hiddenSize][inputSize];
            dBh = new double[hiddenSize];
            dWho = new double[outputSize][hiddenSize];
            dBo = new double[outputSize];
        }

        /** Suma "otro" dentro de este acumulador (usado para combinar resultados de varios hilos). */
        public void addInPlace(Gradients other) {
            for (int j = 0; j < dWih.length; j++)
                for (int i = 0; i < dWih[j].length; i++)
                    dWih[j][i] += other.dWih[j][i];
            for (int j = 0; j < dBh.length; j++) dBh[j] += other.dBh[j];
            for (int k = 0; k < dWho.length; k++)
                for (int j = 0; j < dWho[k].length; j++)
                    dWho[k][j] += other.dWho[k][j];
            for (int k = 0; k < dBo.length; k++) dBo[k] += other.dBo[k];
            count += other.count;
            lossSum += other.lossSum;
            correct += other.correct;
        }
    }

    /**
     * Calcula (sin modificar la red) los gradientes acumulados para un
     * shard del dataset. Metodo seguro para llamar concurrentemente desde
     * varios hilos sobre la MISMA instancia de NeuralNetwork, siempre que
     * nadie este llamando a applyGradients al mismo tiempo (el orquestador
     * de entrenamiento se encarga de esa exclusion: primero todos los
     * hilos calculan, despues -y solo despues- se aplica la actualizacion).
     */
    public Gradients computeGradients(List<Sample> shard) {
        Gradients g = new Gradients(hiddenSize, inputSize, outputSize);

        for (Sample sample : shard) {
            double[] input = sample.features;
            double[] hidden = computeHidden(input);
            double[] output = computeOutput(hidden);

            double[] target = new double[outputSize];
            target[sample.label] = 1.0;

            double[] dz = new double[outputSize]; // derivada softmax+cross-entropy
            for (int k = 0; k < outputSize; k++) dz[k] = output[k] - target[k];

            for (int k = 0; k < outputSize; k++) {
                for (int j = 0; j < hiddenSize; j++) {
                    g.dWho[k][j] += dz[k] * hidden[j];
                }
                g.dBo[k] += dz[k];
            }

            double[] dHidden = new double[hiddenSize];
            for (int j = 0; j < hiddenSize; j++) {
                double sum = 0;
                for (int k = 0; k < outputSize; k++) sum += dz[k] * weightsHiddenOutput[k][j];
                dHidden[j] = sum * hidden[j] * (1 - hidden[j]); // derivada sigmoide
            }

            for (int j = 0; j < hiddenSize; j++) {
                for (int i = 0; i < inputSize; i++) {
                    g.dWih[j][i] += dHidden[j] * input[i];
                }
                g.dBh[j] += dHidden[j];
            }

            g.count++;
            double p = Math.max(output[sample.label], 1e-12);
            g.lossSum += -Math.log(p);
            if (sample.label == argmax(output)) g.correct++;
        }
        return g;
    }

    private static int argmax(double[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[best]) best = i;
        return best;
    }

    /** Aplica el gradiente promedio del lote a los pesos (descenso de gradiente). Llamar SOLO desde el hilo principal. */
    public void applyGradients(Gradients g, double learningRate) {
        if (g.count == 0) return;
        double scale = learningRate / g.count;
        for (int j = 0; j < hiddenSize; j++) {
            for (int i = 0; i < inputSize; i++) {
                weightsInputHidden[j][i] -= scale * g.dWih[j][i];
            }
            biasHidden[j] -= scale * g.dBh[j];
        }
        for (int k = 0; k < outputSize; k++) {
            for (int j = 0; j < hiddenSize; j++) {
                weightsHiddenOutput[k][j] -= scale * g.dWho[k][j];
            }
            biasOutput[k] -= scale * g.dBo[k];
        }
    }

    public double[][] getWeightsInputHidden() { return weightsInputHidden; }
    public double[] getBiasHidden() { return biasHidden; }
    public double[][] getWeightsHiddenOutput() { return weightsHiddenOutput; }
    public double[] getBiasOutput() { return biasOutput; }
}
