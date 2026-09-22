import gzip, struct, io

def read_string(f):
    (length,) = struct.unpack('>H', f.read(2))
    return f.read(length).decode('utf-8')

def read_payload(f, tag_type):
    if tag_type == 1: return struct.unpack('>b', f.read(1))[0]
    if tag_type == 2: return struct.unpack('>h', f.read(2))[0]
    if tag_type == 3: return struct.unpack('>i', f.read(4))[0]
    if tag_type == 4: return struct.unpack('>q', f.read(8))[0]
    if tag_type == 5: return struct.unpack('>f', f.read(4))[0]
    if tag_type == 6: return struct.unpack('>d', f.read(8))[0]
    if tag_type == 7:
        (length,) = struct.unpack('>i', f.read(4))
        return f.read(length)
    if tag_type == 8: return read_string(f)
    if tag_type == 9:
        (item_type,) = struct.unpack('>b', f.read(1))
        (length,) = struct.unpack('>i', f.read(4))
        return {'__list_type__': item_type, 'items': [read_payload(f, item_type) for _ in range(length)]}
    if tag_type == 10:
        comp = {}
        while True:
            (t,) = struct.unpack('>b', f.read(1))
            if t == 0: break
            name = read_string(f)
            comp[name] = read_payload(f, t)
        return comp
    if tag_type == 11:
        (length,) = struct.unpack('>i', f.read(4))
        return list(struct.unpack('>%di' % length, f.read(4*length)))
    if tag_type == 12:
        (length,) = struct.unpack('>i', f.read(4))
        return list(struct.unpack('>%dq' % length, f.read(8*length)))
    raise ValueError(f"Unknown tag type {tag_type}")

def parse_nbt_file(path):
    with gzip.open(path, 'rb') as fh:
        data = fh.read()
    f = io.BytesIO(data)
    (root_type,) = struct.unpack('>b', f.read(1))
    read_string(f)  # root name
    return read_payload(f, root_type)

def decode_varints(data_bytes, total):
    indices = [0]*total
    pos = 0
    i = 0
    n = len(data_bytes)
    while i < n and pos < total:
        result = 0
        shift = 0
        while True:
            b = data_bytes[i]; i += 1
            result |= (b & 0x7F) << shift
            if (b & 0x80) == 0:
                break
            shift += 7
        indices[pos] = result
        pos += 1
    return indices
