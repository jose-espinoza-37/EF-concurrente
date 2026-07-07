package cc4p1.pesos;

import cc4p1.entrenamiento.TrainingConfig;

/**
 * Uso:
 *   java cc4p1.pesos.WeightsServerMain config/training.properties
 *
 * Lee el puerto y la ruta del modelo desde el mismo training.properties
 * usado por el entrenamiento, para no duplicar configuracion.
 */
public class WeightsServerMain {
    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config/training.properties";
        TrainingConfig config = new TrainingConfig(configPath);

        WeightsServer server = new WeightsServer(config.weightsServerPort, config.modelOutputPath);
        Thread t = new Thread(server, "weights-server");
        t.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        t.join();
    }
}
