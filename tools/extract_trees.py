import sys, os, re, json, gzip
sys.path.insert(0, os.path.dirname(__file__))
from nbt_lib import parse_nbt_file, decode_varints

def get_schematic_data(root):
    # v3 wraps everything under "Schematic", v2 is flat at root.
    sch = root.get('Schematic', root)
    width, height, length = sch['Width'], sch['Height'], sch['Length']
    blocks = sch.get('Blocks', sch)  # v3 nests Palette/Data under "Blocks", v2 has them at sch level
    palette = blocks['Palette']
    data_key = 'Data' if 'Data' in blocks else 'BlockData'
    data_bytes = blocks[data_key]
    return width, height, length, palette, data_bytes

def classify(name):
    base, _, states = name.partition('[')
    base = base.replace('minecraft:', '')
    if base == 'air':
        return None
    axis = None
    m = re.search(r'axis=([xyz])', states)
    if m: axis = m.group(1).upper()

    if base.endswith('_log'):
        return f'LOG_{axis or "Y"}'
    if base.endswith('_wood'):
        return f'WOOD_{axis or "Y"}'
    if base.endswith('_leaves'):
        return 'LEAVES'
    if base == 'mangrove_roots':
        return 'ROOTS'
    # anything else (vines, roots, fruit, etc.): decorative, skip — no safe way to
    # recover attachment orientation (e.g. vine) from just the base id here.
    return None

def extract_tree(path):
    root = parse_nbt_file(path)
    width, height, length, palette, data_bytes = get_schematic_data(root)
    inv_palette = {v: k for k, v in palette.items()}
    total = width * height * length
    indices = decode_varints(data_bytes, total)

    def idx(x, y, z):
        return y*(length*width) + z*width + x

    positions = []  # (x,y,z,cls)
    log_positions = []
    for y in range(height):
        for z in range(length):
            for x in range(width):
                v = indices[idx(x,y,z)]
                name = inv_palette.get(v)
                if name is None: continue
                cls = classify(name)
                if cls is None: continue
                positions.append((x,y,z,cls))
                if cls.startswith('LOG') or cls.startswith('WOOD'):
                    log_positions.append((x,y,z))

    if not positions:
        return None

    if log_positions:
        min_y = min(p[1] for p in log_positions)
        base_candidates = [p for p in log_positions if p[1] == min_y]
    else:
        min_y = min(p[1] for p in positions)
        base_candidates = [(p[0],p[1],p[2]) for p in positions if p[1] == min_y]

    cx = sum(p[0] for p in positions) / len(positions)
    cz = sum(p[2] for p in positions) / len(positions)
    ax, ay, az = min(base_candidates, key=lambda p: (p[0]-cx)**2 + (p[2]-cz)**2)

    blocks = [(x-ax, y-ay, z-az, cls) for (x,y,z,cls) in positions]
    tree_height = max(p[1] for p in positions) - min(p[1] for p in positions) + 1
    spread = max(max(p[0] for p in positions) - min(p[0] for p in positions) + 1,
                 max(p[2] for p in positions) - min(p[2] for p in positions) + 1)
    return {'size': len(blocks), 'height': tree_height, 'spread': spread, 'blocks': blocks}

def export_species(folder, out_path):
    files = sorted(f for f in os.listdir(folder) if f.endswith('.schem') and 'package' not in f.lower() and 'all_trees' not in f.lower())
    trees = []
    for fname in files:
        t = extract_tree(os.path.join(folder, fname))
        if t:
            trees.append(t)
        else:
            print(f"  WARNING: no blocks extracted from {fname}")
    trees.sort(key=lambda t: t['height'])

    lines = [str(len(trees))]
    for t in trees:
        lines.append(f"{t['size']},{t['height']},{t['spread']}")
        lines.append(';'.join(f"{dx},{dy},{dz},{cls}" for dx,dy,dz,cls in t['blocks']))
    text = '\n'.join(lines)
    with gzip.open(out_path, 'wt', encoding='utf-8') as f:
        f.write(text)
    sizes = [t['height'] for t in trees]
    print(f"{os.path.basename(folder)}: {len(trees)} trees, heights {min(sizes) if sizes else 0}-{max(sizes) if sizes else 0}, out={out_path} ({os.path.getsize(out_path)} bytes)")

if __name__ == '__main__':
    bundle_root = sys.argv[1]
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for entry in sorted(os.listdir(bundle_root)):
        full = os.path.join(bundle_root, entry)
        if not os.path.isdir(full): continue
        # folder name like "Oak_Trees_18_pcs" -> species "oak"
        m = re.match(r'([A-Za-z_]+?)_Trees?_\d+_pcs', entry)
        species = (m.group(1) if m else entry).lower()
        out_path = os.path.join(out_dir, f"{species}_variants.txt.gz")
        export_species(full, out_path)
