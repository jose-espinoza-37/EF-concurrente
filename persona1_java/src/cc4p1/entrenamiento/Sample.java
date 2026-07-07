package cc4p1.entrenamiento;

/** Una muestra de entrenamiento: vector de caracteristicas + indice de clase. */
public class Sample {
    public final double[] features;
    public final int label;

    public Sample(double[] features, int label) {
        this.features = features;
        this.label = label;
    }
}
