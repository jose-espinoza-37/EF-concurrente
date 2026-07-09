

import struct
from dataclasses import dataclass
from typing import List

MAGIC = 0x43433450
VERSION_SOPORTADA = 1


@dataclass
class ModeloCargado:
    input_size: int
    hidden_size: int
    output_size: int
    image_width: int
    image_height: int
    w_ih: List[List[float]]   # [hidden][input]
    b_h: List[float]          # [hidden]
    w_ho: List[List[float]]   # [output][hidden]
    b_o: List[float]          # [output]
    class_names: List[str]


def load_model(path: str) -> ModeloCargado:
    with open(path, "rb") as f:
        data = f.read()

    offset = 0

    def read_int() -> int:
        nonlocal offset
        (value,) = struct.unpack_from(">i", data, offset)
        offset += 4
        return value

    def read_double() -> float:
        nonlocal offset
        (value,) = struct.unpack_from(">d", data, offset)
        offset += 8
        return value

    magic = read_int()
    if magic != MAGIC:
        raise ValueError("Archivo de pesos invalido (magic incorrecto: 0x%x)" % (magic & 0xFFFFFFFF))

    version = read_int()
    if version != VERSION_SOPORTADA:
        raise ValueError("Version de formato no soportada: %d" % version)

    input_size = read_int()
    hidden_size = read_int()
    output_size = read_int()
    image_width = read_int()
    image_height = read_int()

    w_ih = [[read_double() for _ in range(input_size)] for _ in range(hidden_size)]
    b_h = [read_double() for _ in range(hidden_size)]
    w_ho = [[read_double() for _ in range(hidden_size)] for _ in range(output_size)]
    b_o = [read_double() for _ in range(output_size)]

    num_classes = read_int()
    class_names = []
    for _ in range(num_classes):
        (length,) = struct.unpack_from(">H", data, offset)
        offset += 2
        name = data[offset:offset + length].decode("utf-8")
        offset += length
        class_names.append(name)

    return ModeloCargado(
        input_size=input_size,
        hidden_size=hidden_size,
        output_size=output_size,
        image_width=image_width,
        image_height=image_height,
        w_ih=w_ih,
        b_h=b_h,
        w_ho=w_ho,
        b_o=b_o,
        class_names=class_names,
    )
