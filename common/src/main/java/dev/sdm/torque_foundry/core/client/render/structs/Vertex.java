package dev.sdm.torque_foundry.core.client.render.structs;

/**
 * Вершина квада модели: позиция, нормаль и UV.
 *
 * <p>Mutable POD-структура: поля публичные и меняются напрямую без геттеров —
 * это hot path рендера, аллокации и вызовы методов здесь измеряются.
 */
public class Vertex {
    public float x;
    public float y;
    public float z;
    public float normalX;
    public float normalY;
    public float normalZ;
    public float u;
    public float v;
}
