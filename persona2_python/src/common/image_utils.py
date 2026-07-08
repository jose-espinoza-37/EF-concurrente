"""
Redimensiona un buffer RGB crudo por "nearest neighbor". Se implementa a
mano (sin Pillow/OpenCV) por la misma razon que bmp.py: la biblioteca
estandar de Python no trae procesamiento de imagenes. Nearest-neighbor es
suficiente aqui porque el modelo ya fue entrenado sobre imagenes pequeñas
(32x32) y lo unico que necesitamos es que la camara entregue el mismo
tamaño de entrada que espera la red neuronal.
"""


def resize_nearest(rgb_bytes: bytes, src_w: int, src_h: int, dst_w: int, dst_h: int) -> bytes:
    if src_w == dst_w and src_h == dst_h:
        return rgb_bytes

    out = bytearray(dst_w * dst_h * 3)
    for dy in range(dst_h):
        sy = min(src_h - 1, (dy * src_h) // dst_h)
        for dx in range(dst_w):
            sx = min(src_w - 1, (dx * src_w) // dst_w)
            src_idx = (sy * src_w + sx) * 3
            dst_idx = (dy * dst_w + dx) * 3
            out[dst_idx:dst_idx + 3] = rgb_bytes[src_idx:src_idx + 3]
    return bytes(out)
