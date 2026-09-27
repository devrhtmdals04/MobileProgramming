"""Build the original Jade Causeway art kit. Run with Blender --background.

Blender coordinates: X across the road, Y into the distance, Z up.
glTF converts these to Godot's X, -Z, Y convention automatically.
No external add-ons, textures, or network access are required.
"""
import math
import random
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "assets" / "environment"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(29)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)


def material(name, color, metallic=0.0):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*color, 1)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*color, 1)
    bsdf.inputs["Roughness"].default_value = 0.78
    bsdf.inputs["Metallic"].default_value = metallic
    return mat


STONE = [material("Limestone_%d" % i, c) for i, c in enumerate([
    (0.55, 0.65, 0.58), (0.66, 0.73, 0.62), (0.73, 0.77, 0.65),
    (0.62, 0.68, 0.56), (0.77, 0.78, 0.64)])]
DARK = material("Deep_jade", (0.10, 0.29, 0.27))
JADE = material("Glazed_turquoise", (0.08, 0.56, 0.47), 0.2)
GOLD = material("Weathered_brass", (0.91, 0.57, 0.16), 0.45)
LEAF = [material("Foliage_%d" % i, c) for i, c in enumerate([
    (0.06, 0.29, 0.19), (0.10, 0.43, 0.23), (0.26, 0.54, 0.27), (0.44, 0.62, 0.30)])]
ROCK = [material("Cliff_%d" % i, c) for i, c in enumerate([
    (0.19, 0.35, 0.31), (0.28, 0.44, 0.36), (0.37, 0.49, 0.38)])]
WOOD = material("Palm_bark", (0.30, 0.23, 0.13))
CORAL = material("Coral_cloth", (0.89, 0.29, 0.13))


def finish(obj, mat, name):
    obj.name = name
    obj.data.materials.append(mat)
    return obj


def box(name, pos, size, mat, bevel=0.04):
    bpy.ops.mesh.primitive_cube_add(size=1, location=pos)
    obj = bpy.context.object
    obj.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        mod = obj.modifiers.new("Soft_chipped_edges", "BEVEL")
        mod.width = bevel
        mod.segments = 1
        bpy.ops.object.modifier_apply(modifier=mod.name)
        mod = obj.modifiers.new("Weighted_normals", "WEIGHTED_NORMAL")
        bpy.ops.object.modifier_apply(modifier=mod.name)
    return finish(obj, mat, name)


def ico(name, pos, size, mat, subdivisions=1):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdivisions, radius=1, location=pos)
    obj = bpy.context.object
    obj.scale = size
    return finish(obj, mat, name)


def cylinder(name, pos, radius, height, mat, vertices=8, top=None):
    bpy.ops.mesh.primitive_cone_add(vertices=vertices, radius1=radius,
        radius2=radius if top is None else top, depth=height, location=pos)
    return finish(bpy.context.object, mat, name)


def branch(a, b, radius, mat):
    direction = Vector(b) - Vector(a)
    obj = cylinder("Branch", (Vector(a) + Vector(b)) / 2, radius, direction.length, mat, 7, radius * 0.7)
    obj.rotation_euler = direction.to_track_quat("Z", "Y").to_euler()


def leaf(pos, angle, length, width, mat):
    # A ridged, pointed frond with its tip drooping. Real geometry catches the light.
    verts = [(0, 0, 0), (-width, length*.4, .18), (0, length*.48, .40),
             (width, length*.4, .18), (0, length, -.42)]
    mesh = bpy.data.meshes.new("Frond")
    mesh.from_pydata(verts, [], [(0, 2, 1), (0, 3, 2), (1, 2, 4), (2, 3, 4)])
    obj = bpy.data.objects.new("Palm_frond", mesh)
    bpy.context.collection.objects.link(obj)
    obj.location = pos
    obj.rotation_euler.z = angle
    finish(obj, mat, "Palm_frond")
    # Double-sided leaves in the glTF material.
    mat.use_backface_culling = False


def palm(x, y, z, height):
    bend = random.uniform(-0.75, 0.75)
    points = [(x, y, z), (x+bend*.25, y, z+height*.35),
              (x+bend*.7, y+.12, z+height*.72), (x+bend, y+.35, z+height)]
    for a, b in zip(points, points[1:]):
        branch(a, b, .19, WOOD)
    tip = points[-1]
    for i in range(9):
        leaf(tip, i*math.tau/9+random.random()*.2, random.uniform(2.3, 3.2), .45, LEAF[i % 4])
    ico("Palm_heart", tip, (.4, .4, .45), LEAF[1])


def export(name):
    # Join by material to keep mobile draw calls bounded, while preserving flat normals.
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    by_mat = {}
    for obj in meshes:
        by_mat.setdefault(obj.data.materials[0].name, []).append(obj)
    for objects in by_mat.values():
        bpy.ops.object.select_all(action="DESELECT")
        for obj in objects:
            obj.select_set(True)
        bpy.context.view_layer.objects.active = objects[0]
        bpy.ops.object.join()
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.export_scene.gltf(filepath=str(OUT / (name+".glb")), export_format="GLB",
        use_selection=True, export_animations=False, export_cameras=False, export_lights=False)
    bpy.ops.object.delete(use_global=False)
    print("BUILT", name, flush=True)


# 24-metre seamless bridge segment, top surface at height zero.
box("Foundation", (0, 0, -.52), (7.6, 24, .9), DARK, .10)
for row in range(16):
    y = -11.25 + row * 1.5
    for lane in range(3):
        x = (lane-1)*2.36
        box("Paving", (x, y, -.13), (2.30, 1.44, .26), random.choice(STONE), .055)
    for x in [-3.63, 3.63]:
        box("Coping", (x, y, .05), (.35, 1.45, .36), STONE[2], .045)
    if row % 3 == 0:
        for x in [-1.18, 1.18]:
            ob = box("Lane_inlay", (x, y, .016), (.065, .32, .022), GOLD, .01)
    if row % 4 == 0:
        for x in [-3.95, 3.95]:
            box("Pier_cap", (x, y, .1), (.75, .85, .55), STONE[1], .07)
            box("Pier", (x, y, -2.1), (.5, .6, 4.0), DARK)
            cylinder("Pier_gold", (x, y, .49), .20, .14, GOLD)
            ico("Jade_lantern", (x, y, .77), (.16, .16, .28), JADE)
    if row % 2 == 0:
        for side in [-1, 1]:
            leaf((side*3.7, y+.2, .18), side*1.3, .75, .24, LEAF[1])
box("Brass_edge", (-3.79, 0, -.12), (.055, 24, .09), GOLD, 0)
box("Brass_edge", (3.79, 0, -.12), (.055, 24, .09), GOLD, 0)
export("causeway")

# A 48-metre canyon section. Tall silhouettes frame the route, leaving the centre open.
for side in [-1, 1]:
    for i in range(8):
        x = side * random.uniform(9.8, 17.0)
        y = -22 + i*6.5
        h = random.uniform(3.5, 10.0)
        ico("Basalt_island", (x, y, -2), (random.uniform(4, 6), 5, h), random.choice(ROCK), 2)
        ico("Moss_crown", (x, y, h*.56-1), (3.7, 3.6, 1.1), LEAF[0], 1)
        if i % 2 == 0:
            palm(x-side*1.6, y, h*.65-1, random.uniform(4, 6))
        for k in range(3):
            ico("Shrub", (x+random.uniform(-3,3), y+random.uniform(-2,2), h*.6-1),
                (1.4, 1.4, 1), random.choice(LEAF), 1)
    for i in range(3):
        x = side*(5.3+random.random())
        y = -17 + i*17
        box("Ruin_plinth", (x,y,-1.1), (1.5,1.8,2.6), DARK, .15)
        for h in range(4):
            box("Broken_ruin", (x,y,h*.65+.45), (1.1-h*.10,1.1-h*.10,.59), STONE[h%5], .06)
        cylinder("Ruin_crown", (x,y,3.15), .42,.22,GOLD)
        for n in range(4):
            leaf((x-side*.5,y,2-n*.5), side*1.5,.9,.28,LEAF[1])
export("canyon")

# Architectural landmark. A stepped lintel, carved shafts and turquoise details.
for side in [-1, 1]:
    x = side*4.65
    box("Footing", (x,0,.22), (2.0,2.2,.45), STONE[2], .09)
    box("Footing", (x,0,.63), (1.65,1.8,.38), DARK, .07)
    for i in range(7):
        box("Column_course", (x,0,1.22+i*.72), (1.18,1.25,.66), STONE[i%5], .06)
    for z in [1.1,3.4,5.65]:
        box("Brass_band", (x,0,z), (1.3,1.37,.10), GOLD, .02)
    box("Jade_relief", (x,-.65,3.65), (.54,.055,1.45), JADE, .03)
    box("Capital", (x,0,6.12), (1.85,1.8,.5), STONE[2], .09)
box("Lintel", (0,0,6.55), (11.7,1.8,.5), DARK, .09)
box("Crown", (0,0,7.0), (12.4,2.0,.35), STONE[2], .10)
box("Gold_lintel", (0,-.93,6.53), (11.4,.055,.08), GOLD, .01)
box("Upper_tier", (0,0,7.45), (6.2,1.8,.65), STONE[1], .10)
box("Upper_tier", (0,0,7.98), (3.6,1.6,.4), DARK, .07)
ico("Sun_emblem", (0,-1.0,7.15), (.65,.13,.65), GOLD, 1)
for x in [-5.6,-3.5,2.9,5.4]:
    for i in range(random.randint(3,6)):
        leaf((x,-.9,7-i*.37), math.pi*.5,.7,.25, LEAF[i%3])
export("sun_gate")

# Obstacle silhouettes: low jade vault, coral overhead cloth, high ruined pillar.
box("Vault", (0,0,.42), (1.72,1.0,.84), DARK, .12)
box("Vault_top", (0,0,.83), (1.9,1.13,.16), JADE, .06)
for x in [-.66, .66]:
    box("Gold_strap", (x,-.52,.46), (.12,.07,.76), GOLD,.02)
export("vault")

for x in [-.99,.99]:
    box("Overhead_post", (x,0,1.3), (.16,.24,2.6), GOLD,.03)
box("Slide_beam", (0,0,2.48), (2.18,.6,.35), STONE[2],.05)
box("Slide_cloth", (0,0,1.83), (1.86,.10,1.0), CORAL,.035)
box("Cloth_hem", (0,-.065,1.34), (1.86,.025,.07), GOLD,0)
ico("Cloth_emblem", (0,-.09,1.86), (.22,.06,.3), GOLD)
export("slide_gate")

box("Pillar_base", (0,0,.2), (1.82,1.4,.4), DARK,.08)
for i in range(4):
    obj = box("Pillar_block", (0,0,.65+i*.56), (1.38,1.08,.51), STONE[i],.08)
    obj.rotation_euler.z = (i%2-.5)*.08
box("Pillar_top", (0,0,2.76), (1.68,1.32,.24), JADE,.06)
box("Jade_mark", (0,-.56,1.65), (.36,.06,.82), JADE,.04)
export("pillar")

bpy.ops.mesh.primitive_torus_add(major_radius=.28, minor_radius=.065, major_segments=12,
    minor_segments=5, location=(0,0,0), rotation=(math.pi/2,0,0))
finish(bpy.context.object,GOLD,"Relic_ring")
ico("Relic_heart", (0,0,0), (.16,.09,.23),GOLD,1)
export("relic")
