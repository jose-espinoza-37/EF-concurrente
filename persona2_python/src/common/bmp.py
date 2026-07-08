"""
La biblioteca estandar de Python (a diferencia de javax.imageio en Java) NO
trae ningun codec de imagenes (ni JPEG ni PNG). Como el enunciado exige usar
solo las librerias base del lenguaje, en vez de instalar Pillow/OpenCV se
implementa aqui un escritor de archivos BMP minimo: el formato BMP sin
compresion es, en esencia, un encabezado de tamaño fijo seguido de los
pixeles crudos, algo perfectamente escribible a mano con el modulo `struct`
de la biblioteca estandar.

Esto es EXACTAMENTE el mismo espiritu que ImageDataset.java: usar solo lo que
trae el lenguaje "de fabrica", documentando el formato en vez de depender de
una libreria externa.
"""

import struct


def save_bmp(path: str, width: int, height: int, rgb_bytes: bytes) -> None:
    """
    Guarda una imagen RGB cruda (rgb_bytes = width*height*3 bytes, fila por
    fila, de arriba hacia abajo, 1 byte por canal R,G,B) como un archivo
    .bmp de 24 bits por pixel, valido y abrible por cualquier visor de
    imagenes estandar.
    """
    if len(rgb_bytes) != width * height * 3:
        raise ValueError("rgb_bytes no tiene el tamano esperado (width*height*3)")

    row_size = (width * 3 + 3) & ~3  # cada fila debe alinearse a multiplos de 4 bytes
    padding = row_size - width * 3
    pixel_data_size = row_size * height

    file_header_size = 14
    info_header_size = 40
    pixel_data_offset = file_header_size + info_header_size
    file_size = pixel_data_offset + pixel_data_size

    # --- Encabezado de archivo BMP (14 bytes) ---
    file_header = struct.pack(
        "<2sIHHI",
        b"BM",           # firma
        file_size,
        0, 0,            # reservados
        pixel_data_offset,
    )

    # --- Encabezado de informacion DIB (BITMAPINFOHEADER, 40 bytes) ---
    info_header = struct.pack(
        "<IiiHHIIiiII",
        info_header_size,
        width,
        height,          # positivo => BMP se guarda de abajo hacia arriba
        1,               # planos de color
        24,              # bits por pixel
        0,               # sin compresion
        pixel_data_size,
        2835, 2835,      # resolucion (~72 DPI), no relevante aqui
        0, 0,            # colores en la paleta
    )

    with open(path, "wb") as f:
        f.write(file_header)
        f.write(info_header)
        # BMP almacena las filas de abajo hacia arriba y en orden B,G,R
        for y in range(height - 1, -1, -1):
            row_start = y * width * 3
            row = rgb_bytes[row_start:row_start + width * 3]
            for x in range(width):
                r, g, b = row[x * 3], row[x * 3 + 1], row[x * 3 + 2]
                f.write(bytes((b, g, r)))
            if padding:
                f.write(b"\x00" * padding)
