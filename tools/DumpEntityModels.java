import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.render.block.entity.HangingSignBlockEntityRenderer;
import net.minecraft.client.render.block.entity.SignBlockEntityRenderer;
import net.minecraft.client.render.entity.model.*;

/**
 * Exports the game's own entity model geometry for the server-side robot camera, as compact JSON: each model is a
 * tree of parts with pivot "o" (1/16 block), rotation "r" (pitch, yaw, roll in radians), scale "s", children "c"
 * and finished faces "f" (four corners of x, y, z, u, v, then the face normal), exactly as the game builds them.
 * The model names and transforms mirror EntityModels.getModels(); the sign models come from the sign renderers. Run it with tools/dump_entity_models.sh.
 */
public class DumpEntityModels {
    public static void main(String[] args) throws Exception {
        Map<String, TexturedModelData> m = new LinkedHashMap<>();
        TexturedModelData biped = TexturedModelData.of(BipedEntityModel.getModelData(Dilation.NONE, 0.0F), 64, 64);
        m.put("biped", biped);
        m.put("biped_baby", biped.transform(BipedEntityModel.BABY_TRANSFORMER));
        m.put("husk", biped.transform(ModelTransformer.scaling(1.0625F)));
        m.put("husk_baby", biped.transform(BipedEntityModel.BABY_TRANSFORMER).transform(ModelTransformer.scaling(1.0625F)));
        m.put("player", TexturedModelData.of(PlayerEntityModel.getTexturedModelData(Dilation.NONE, false), 64, 64));
        m.put("player_slim", TexturedModelData.of(PlayerEntityModel.getTexturedModelData(Dilation.NONE, true), 64, 64));
        TexturedModelData drowned = DrownedEntityModel.getTexturedModelData(Dilation.NONE);
        m.put("drowned", drowned);
        m.put("drowned_baby", drowned.transform(BipedEntityModel.BABY_TRANSFORMER));
        m.put("drowned_outer", DrownedEntityModel.getTexturedModelData(new Dilation(0.25F)));
        TexturedModelData zombieVillager = ZombieVillagerEntityModel.getTexturedModelData();
        m.put("zombie_villager", zombieVillager);
        m.put("zombie_villager_baby", zombieVillager.transform(BipedEntityModel.BABY_TRANSFORMER));
        TexturedModelData skeleton = SkeletonEntityModel.getTexturedModelData();
        m.put("skeleton", skeleton);
        m.put("wither_skeleton", skeleton.transform(ModelTransformer.scaling(1.2F)));
        m.put("skeleton_outer_025", TexturedModelData.of(BipedEntityModel.getModelData(new Dilation(0.25F), 0.0F), 64, 32));
        m.put("bogged", BoggedEntityModel.getTexturedModelData());
        m.put("bogged_outer", TexturedModelData.of(BipedEntityModel.getModelData(new Dilation(0.2F), 0.0F), 64, 32));
        m.put("parched", SkeletonEntityModel.getParchedTexturedModelData());
        TexturedModelData villager = TexturedModelData.of(VillagerResemblingModel.getModelData(), 64, 64).transform(ModelTransformer.scaling(0.9375F));
        m.put("villager", villager);
        m.put("villager_baby", villager.transform(VillagerResemblingModel.BABY_TRANSFORMER));
        m.put("enderman", EndermanEntityModel.getTexturedModelData());
        m.put("creeper", CreeperEntityModel.getTexturedModelData(Dilation.NONE));
        TexturedModelData spider = SpiderEntityModel.getTexturedModelData();
        m.put("spider", spider);
        m.put("cave_spider", spider.transform(ModelTransformer.scaling(0.7F)));
        TexturedModelData cow = CowEntityModel.getTexturedModelData();
        TexturedModelData coldCow = ColdCowEntityModel.getTexturedModelData();
        TexturedModelData warmCow = WarmCowEntityModel.getTexturedModelData();
        m.put("cow", cow); m.put("cow_baby", cow.transform(CowEntityModel.BABY_TRANSFORMER));
        m.put("cold_cow", coldCow); m.put("cold_cow_baby", coldCow.transform(CowEntityModel.BABY_TRANSFORMER));
        m.put("warm_cow", warmCow); m.put("warm_cow_baby", warmCow.transform(CowEntityModel.BABY_TRANSFORMER));
        TexturedModelData pig = PigEntityModel.getTexturedModelData(Dilation.NONE);
        TexturedModelData coldPig = ColdPigEntityModel.getTexturedModelData(Dilation.NONE);
        m.put("pig", pig); m.put("pig_baby", pig.transform(PigEntityModel.BABY_TRANSFORMER));
        m.put("cold_pig", coldPig); m.put("cold_pig_baby", coldPig.transform(PigEntityModel.BABY_TRANSFORMER));
        TexturedModelData sheep = SheepEntityModel.getTexturedModelData();
        TexturedModelData wool = SheepWoolEntityModel.getTexturedModelData();
        m.put("sheep", sheep); m.put("sheep_baby", sheep.transform(SheepEntityModel.BABY_TRANSFORMER));
        m.put("sheep_wool", wool); m.put("sheep_wool_baby", wool.transform(SheepEntityModel.BABY_TRANSFORMER));
        TexturedModelData chicken = ChickenEntityModel.getTexturedModelData();
        TexturedModelData coldChicken = ColdChickenEntityModel.getTexturedModelData();
        m.put("chicken", chicken); m.put("chicken_baby", chicken.transform(ChickenEntityModel.BABY_TRANSFORMER));
        m.put("cold_chicken", coldChicken); m.put("cold_chicken_baby", coldChicken.transform(ChickenEntityModel.BABY_TRANSFORMER));
        m.put("slime", SlimeEntityModel.getInnerTexturedModelData());
        m.put("slime_outer", SlimeEntityModel.getOuterTexturedModelData());
        m.put("magma_cube", MagmaCubeEntityModel.getTexturedModelData());
        m.put("sign_standing", SignBlockEntityRenderer.getTexturedModelData(true));
        m.put("sign_wall", SignBlockEntityRenderer.getTexturedModelData(false));
        m.put("hanging_sign_wall", HangingSignBlockEntityRenderer.getTexturedModelData(HangingSignBlockEntityRenderer.AttachmentType.WALL));
        m.put("hanging_sign_ceiling", HangingSignBlockEntityRenderer.getTexturedModelData(HangingSignBlockEntityRenderer.AttachmentType.CEILING));
        m.put("hanging_sign_ceiling_middle", HangingSignBlockEntityRenderer.getTexturedModelData(HangingSignBlockEntityRenderer.AttachmentType.CEILING_MIDDLE));

        StringBuilder out = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, TexturedModelData> entry : m.entrySet()) {
            out.append(first ? "" : ",\n").append('"').append(entry.getKey()).append("\":");
            part(out, "root", entry.getValue().createModel());
            first = false;
        }
        out.append("\n}\n");
        System.out.print(out);
        System.err.println("exported " + m.size());
    }

    @SuppressWarnings("unchecked")
    static void part(StringBuilder out, String name, ModelPart part) throws Exception {
        out.append("{\"o\":[").append(f(part.originX)).append(',').append(f(part.originY)).append(',').append(f(part.originZ)).append(']');
        if (part.pitch != 0 || part.yaw != 0 || part.roll != 0) out.append(",\"r\":[").append(f(part.pitch)).append(',').append(f(part.yaw)).append(',').append(f(part.roll)).append(']');
        if (part.xScale != 1 || part.yScale != 1 || part.zScale != 1) out.append(",\"s\":[").append(f(part.xScale)).append(',').append(f(part.yScale)).append(',').append(f(part.zScale)).append(']');
        Field cuboidsField = ModelPart.class.getDeclaredField("cuboids"); cuboidsField.setAccessible(true);
        Field childrenField = ModelPart.class.getDeclaredField("children"); childrenField.setAccessible(true);
        List<ModelPart.Cuboid> cuboids = (List<ModelPart.Cuboid>) cuboidsField.get(part);
        StringBuilder faces = new StringBuilder();
        for (ModelPart.Cuboid cuboid : cuboids) {
            for (ModelPart.Quad quad : cuboid.sides) {
                faces.append(faces.length() > 0 ? "," : "").append('[');
                for (ModelPart.Vertex v : quad.vertices()) faces.append(f(v.x())).append(',').append(f(v.y())).append(',').append(f(v.z())).append(',').append(f(v.u())).append(',').append(f(v.v())).append(',');
                faces.append(f(quad.direction().x())).append(',').append(f(quad.direction().y())).append(',').append(f(quad.direction().z())).append(']');
            }
        }
        if (faces.length() > 0) out.append(",\"f\":[").append(faces).append(']');
        Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);
        if (!children.isEmpty()) {
            out.append(",\"c\":{");
            boolean first = true;
            for (Map.Entry<String, ModelPart> child : new TreeMap<>(children).entrySet()) {
                out.append(first ? "" : ",").append('"').append(child.getKey()).append("\":");
                part(out, child.getKey(), child.getValue());
                first = false;
            }
            out.append('}');
        }
        out.append('}');
    }

    static String f(float value) {
        if (value == Math.rint(value)) return Integer.toString((int) value);
        String s = String.format(Locale.ROOT, "%.5f", value);
        s = s.replaceAll("0+$", "");
        return s.equals("-0") ? "0" : s;
    }
}
