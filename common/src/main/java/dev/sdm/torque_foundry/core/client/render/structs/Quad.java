package dev.sdm.torque_foundry.core.client.render.structs;

/**
 * Квад модели: ровно 4 вершины (порядок CCW наружу) и флаг инверсии нормали.
 *
 * <p>Для треугольников из glTF 4-я вершина вырождена (d == c): рендер всегда
 * идёт по 4 вершинам без ветвлений формата.
 */
public class Quad {
    /**
     * Ровно 4 вершины, CCW наружу.
     */
    public Vertex[] vertices;
    public boolean invertNormal;
}
