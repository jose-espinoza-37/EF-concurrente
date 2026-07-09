package cc4p1.entrenamiento;

import java.util.List;
import java.util.Random;

public class NeuralNetwork {

    public final int inputSize;
    public final int hiddenSize;
    public final int outputSize;

    private final double[][] weightsInputHidden;  // [hidden][input]
    private final double[] biasHidden;             // [hidden]
    private final double[][] weightsHiddenOutput;  // [output][hidden]
    private final double[] biasOutput;             // [output]


    private final double[][] velocityWih;
    private final double[] velocityBh;
    private final double[][] velocityWho;
    private final double[] velocityBo;

    public NeuralNetwork(int inputSize, int hiddenSize, int outputSize, long seed) {
        this.inputSize = inputSize;
        this.hiddenSize = hiddenSize;
        this.outputSize = outputSize;
        Random rnd = new Random(seed);

        this.weightsInputHidden = new double[hiddenSize][inputSize];
        this.biasHidden = new double[hiddenSize];
        this.weightsHiddenOutput = new double[outputSize][hiddenSize];
        this.biasOutput = new double[outputSize];

        this.velocityWih = new double[hiddenSize][inputSize];
        this.velocityBh = new double[hiddenSize];
        this.velocityWho = new double[outputSize][hiddenSize];
        this.velocityBo = new double[outputSize];

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

    /** Constructor usado al cargar un modelo ya entrenado desde disco (no necesita estado de optimizador). */
    public NeuralNetwork(double[][] wih, double[] bh, double[][] who, double[] bo) {
        this.hiddenSize = wih.length;
        this.inputSize = wih[0].length;
        this.outputSize = who.length;
        this.weightsInputHidden = wih;
        this.biasHidden = bh;
        this.weightsHiddenOutput = who;
        this.biasOutput = bo;

        this.velocityWih = new double[hiddenSize][inputSize];
        this.velocityBh = new double[hiddenSize];
        this.velocityWho = new double[outputSize][hiddenSize];
        this.velocityBo = new double[outputSize];
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
     * shard del dataset. Seguro para llamar concurrentemente desde varios
     * hilos sobre la MISMA instancia de NeuralNetwork, siempre que nadie
     * este llamando a applyGradients al mismo tiempo.
     */
    public Gradients computeGradients(List<Sample> shard) {
        Gradients g = new Gradients(hiddenSize, inputSize, outputSize);

        for (Sample sample : shard) {
            double[] input = sample.features;
            double[] hidden = computeHidden(input);
            double[] output = computeOutput(hidden);

            double[] target = new double[outputSize];
            target[sample.label] = 1.0;

            double[] dz = new double[outputSize];
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
                dHidden[j] = sum * hidden[j] * (1 - hidden[j]);
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

    /**
     * Aplica el gradiente promedio del lote a los pesos, con momentum y
     * regularizacion L2. Llamar SOLO desde el hilo principal (no es
     * thread-safe, no hace falta que lo sea: se llama una vez por epoca,
     * despues de que todos los hilos de computeGradients ya terminaron).
     *
     * @param momentum      factor de inercia de la velocidad acumulada (ej. 0.9). 0 = SGD clasico sin momentum.
     * @param l2Regularization intensidad de weight decay (ej. 0.0005). 0 = sin regularizacion.
     */
    public void applyGradients(Gradients g, double learningRate, double momentum, double l2Regularization) {
        if (g.count == 0) return;
        double scale = learningRate / g.count;

        for (int j = 0; j < hiddenSize; j++) {
            for (int i = 0; i < inputSize; i++) {
                double grad = scale * g.dWih[j][i] + l2Regularization * weightsInputHidden[j][i];
                velocityWih[j][i] = momentum * velocityWih[j][i] - grad;
                weightsInputHidden[j][i] += velocityWih[j][i];
            }
            double gradB = scale * g.dBh[j];
            velocityBh[j] = momentum * velocityBh[j] - gradB;
            biasHidden[j] += velocityBh[j];
        }
        for (int k = 0; k < outputSize; k++) {
            for (int j = 0; j < hiddenSize; j++) {
                double grad = scale * g.dWho[k][j] + l2Regularization * weightsHiddenOutput[k][j];
                velocityWho[k][j] = momentum * velocityWho[k][j] - grad;
                weightsHiddenOutput[k][j] += velocityWho[k][j];
            }
            double gradB = scale * g.dBo[k];
            velocityBo[k] = momentum * velocityBo[k] - gradB;
            biasOutput[k] += velocityBo[k];
        }
    }

    /** Sobrecarga de compatibilidad: descenso de gradiente simple, sin momentum ni L2. */
    public void applyGradients(Gradients g, double learningRate) {
        applyGradients(g, learningRate, 0.0, 0.0);
    }

    /** Resultado de evaluar la red sobre un conjunto de datos SIN modificar los pesos. */
    public static class EvalResult {
        public final double avgLoss;
        public final double accuracy; // 0..100

        public EvalResult(double avgLoss, double accuracy) {
            this.avgLoss = avgLoss;
            this.accuracy = accuracy;
        }
    }

    /**
     * Evalua la red sobre un conjunto de muestras (tipicamente el set de
     * VALIDACION) sin tocar los pesos -- solo forward pass, ningun gradiente.
     */
    public EvalResult evaluate(List<Sample> data) {
        if (data.isEmpty()) return new EvalResult(0.0, 0.0);
        double lossSum = 0;
        int correct = 0;
        for (Sample s : data) {
            double[] probs = predict(s.features);
            double p = Math.max(probs[s.label], 1e-12);
            lossSum += -Math.log(p);
            if (s.label == argmax(probs)) correct++;
        }
        return new EvalResult(lossSum / data.size(), 100.0 * correct / data.size());
    }

    /** Copia profunda de los pesos actuales (NO incluye el estado de momentum). */
    public static class WeightSnapshot {
        public final double[][] wih;
        public final double[] bh;
        public final double[][] who;
        public final double[] bo;

        WeightSnapshot(double[][] wih, double[] bh, double[][] who, double[] bo) {
            this.wih = wih;
            this.bh = bh;
            this.who = who;
            this.bo = bo;
        }
    }

    public WeightSnapshot snapshot() {
        return new WeightSnapshot(deepCopy(weightsInputHidden), biasHidden.clone(),
                deepCopy(weightsHiddenOutput), biasOutput.clone());
    }

    /** Reconstruye una NeuralNetwork "congelada" a partir de un snapshot previo, lista para guardar/predecir. */
    public static NeuralNetwork fromSnapshot(WeightSnapshot snap) {
        return new NeuralNetwork(snap.wih, snap.bh, snap.who, snap.bo);
    }

    private static double[][] deepCopy(double[][] src) {
        double[][] copy = new double[src.length][];
        for (int i = 0; i < src.length; i++) copy[i] = src[i].clone();
        return copy;
    }

    public double[][] getWeightsInputHidden() { return weightsInputHidden; }
    public double[] getBiasHidden() { return biasHidden; }
    public double[][] getWeightsHiddenOutput() { return weightsHiddenOutput; }
    public double[] getBiasOutput() { return biasOutput; }
}