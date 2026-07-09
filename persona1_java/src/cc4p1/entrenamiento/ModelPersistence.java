package cc4p1.entrenamiento;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


public final class ModelPersistence {

    private static final int MAGIC = 0x43433450; // "CC4P"
    private static final int VERSION = 1;

    private ModelPersistence() {
    }

    public static void save(NeuralNetwork net, List<String> classNames, int imageWidth, int imageHeight,
                             String path) throws IOException {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file.toPath())))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(net.inputSize);
            out.writeInt(net.hiddenSize);
            out.writeInt(net.outputSize);
            out.writeInt(imageWidth);
            out.writeInt(imageHeight);

            for (double[] row : net.getWeightsInputHidden()) {
                for (double v : row) out.writeDouble(v);
            }
            for (double v : net.getBiasHidden()) out.writeDouble(v);

            for (double[] row : net.getWeightsHiddenOutput()) {
                for (double v : row) out.writeDouble(v);
            }
            for (double v : net.getBiasOutput()) out.writeDouble(v);

            out.writeInt(classNames.size());
            for (String name : classNames) {
                byte[] bytes = name.getBytes("UTF-8");
                out.writeShort(bytes.length);
                out.write(bytes);
            }
        }
        System.out.println("[ModelPersistence] Modelo guardado en: " + file.getAbsolutePath()
                + " (" + file.length() + " bytes)");
    }

    public static class LoadedModel {
        public final NeuralNetwork network;
        public final List<String> classNames;
        public final int imageWidth;
        public final int imageHeight;

        public LoadedModel(NeuralNetwork network, List<String> classNames, int imageWidth, int imageHeight) {
            this.network = network;
            this.classNames = classNames;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }

    public static LoadedModel load(String path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(path))))) {
            int magic = in.readInt();
            if (magic != MAGIC) throw new IOException("Archivo de pesos invalido (magic incorrecto)");
            int version = in.readInt();
            if (version != VERSION) throw new IOException("Version de formato no soportada: " + version);

            int inputSize = in.readInt();
            int hiddenSize = in.readInt();
            int outputSize = in.readInt();
            int imageWidth = in.readInt();
            int imageHeight = in.readInt();

            double[][] wih = new double[hiddenSize][inputSize];
            for (int j = 0; j < hiddenSize; j++)
                for (int i = 0; i < inputSize; i++)
                    wih[j][i] = in.readDouble();

            double[] bh = new double[hiddenSize];
            for (int j = 0; j < hiddenSize; j++) bh[j] = in.readDouble();

            double[][] who = new double[outputSize][hiddenSize];
            for (int k = 0; k < outputSize; k++)
                for (int j = 0; j < hiddenSize; j++)
                    who[k][j] = in.readDouble();

            double[] bo = new double[outputSize];
            for (int k = 0; k < outputSize; k++) bo[k] = in.readDouble();

            int numClasses = in.readInt();
            List<String> classNames = new ArrayList<>();
            for (int c = 0; c < numClasses; c++) {
                int len = in.readUnsignedShort();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                classNames.add(new String(bytes, "UTF-8"));
            }

            NeuralNetwork net = new NeuralNetwork(wih, bh, who, bo);
            return new LoadedModel(net, classNames, imageWidth, imageHeight);
        }
    }
}
