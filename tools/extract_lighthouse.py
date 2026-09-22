import sys, os, gzip
sys.path.insert(0, os.path.dirname(__file__))
from nbt_lib import parse_nbt_file, decode_varints

CONTAINER_IDS = {'minecraft:chest', 'minecraft:barrel'}

def main(path, out_path):
    root = parse_nbt_file(path)
    sch = root.get('Schematic', root)
    width, height, length = sch['Width'], sch['Height'], sch['Length']
    palette = sch['Palette']
    data_bytes = sch['BlockData']
    inv_palette = {v: k for k, v in palette.items()}
    total = width * height * length
    indices = decode_varints(data_bytes, total)

    meta = sch['Metadata']
    ax = -meta['WEOffsetX']
    ay = -meta['WEOffsetY']
    az = -meta['WEOffsetZ']

    def idx(x, y, z):
        return y * (length * width) + z * width + x

    blocks = []  # (dx,dy,dz, blockstate_without_minecraft_prefix)
    for y in range(height):
        for z in range(length):
            for x in range(width):
                v = indices[idx(x, y, z)]
                name = inv_palette.get(v)
                if name is None or name == 'minecraft:air':
                    continue
                short = name.replace('minecraft:', '', 1)
                blocks.append((x - ax, y - ay, z - az, short))

    containers = []  # (dx,dy,dz)
    for be in sch['BlockEntities']['items']:
        if be.get('Id') in CONTAINER_IDS:
            px, py, pz = be['Pos']
            containers.append((px - ax, py - ay, pz - az))

    lines = []
    lines.append(f"{len(blocks)}")
    for dx, dy, dz, state in blocks:
        lines.append(f"{dx},{dy},{dz},{state}")
    lines.append(f"CONTAINERS,{len(containers)}")
    for dx, dy, dz in containers:
        lines.append(f"{dx},{dy},{dz}")

    text = '\n'.join(lines)
    with gzip.open(out_path, 'wt', encoding='utf-8') as f:
        f.write(text)
    print(f"blocks={len(blocks)} containers={len(containers)} anchor_local=({ax},{ay},{az}) out={out_path} ({os.path.getsize(out_path)} bytes)")

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
