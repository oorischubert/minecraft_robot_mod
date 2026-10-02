package com.oori.minebot.client;

import com.oori.minebot.MineBotEntity;
import com.oori.minebot.MineBotMod;
import com.oori.minebot.MineBotSkin;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.consume.UseAction;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

public final class MineBotRenderer extends BipedEntityRenderer<MineBotEntity, MineBotRenderState, MineBotModel> {
    private static final Map<MineBotSkin, SkinTextures> TEXTURES = new EnumMap<>(MineBotSkin.class);

    static {
        for (MineBotSkin skin : MineBotSkin.values()) {
            TEXTURES.put(skin, new SkinTextures(
                texture(skin, "idle"),
                texture(skin, "connected"),
                texture(skin, "evil")
            ));
        }
    }

    public MineBotRenderer(EntityRendererFactory.Context context) {
        super(context, new MineBotModel(context.getPart(MineBotModel.LAYER)), 0.45F);
    }

    @Override
    public MineBotRenderState createRenderState() {
        return new MineBotRenderState();
    }

    @Override
    public void updateRenderState(MineBotEntity entity, MineBotRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.connected = entity.isConnected();
        state.evil = entity.isEvil();
        state.skin = entity.getSkin();
    }

    /**
     * Mob renderers only pose arms for spears; this adds the player's poses so the robot is seen drawing a
     * bow, charging or holding a loaded crossbow, raising a shield and so on while use_item holds them.
     */
    @Override
    protected BipedEntityModel.ArmPose getArmPose(MineBotEntity entity, Arm arm) {
        BipedEntityModel.ArmPose mainPose = useArmPose(entity, Hand.MAIN_HAND);
        if (arm == entity.getMainArm()) {
            return mainPose != null ? mainPose : super.getArmPose(entity, arm);
        }
        if (mainPose != null && mainPose.isTwoHanded()) {
            return entity.getOffHandStack().isEmpty() ? BipedEntityModel.ArmPose.EMPTY : BipedEntityModel.ArmPose.ITEM;
        }
        BipedEntityModel.ArmPose offPose = useArmPose(entity, Hand.OFF_HAND);
        return offPose != null ? offPose : super.getArmPose(entity, arm);
    }

    /** The pose for using the item in this hand, as a player would show it, or null when it is not in use. */
    private static BipedEntityModel.ArmPose useArmPose(MineBotEntity entity, Hand hand) {
        ItemStack stack = entity.getStackInHand(hand);
        if (stack.isEmpty()) {
            return null;
        }
        if (!entity.handSwinging && stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
            return BipedEntityModel.ArmPose.CROSSBOW_HOLD;
        }
        if (entity.getActiveHand() != hand || entity.getItemUseTimeLeft() <= 0) {
            return null;
        }
        UseAction action = stack.getUseAction();
        return switch (action) {
            case BLOCK -> BipedEntityModel.ArmPose.BLOCK;
            case BOW -> BipedEntityModel.ArmPose.BOW_AND_ARROW;
            case TRIDENT -> BipedEntityModel.ArmPose.THROW_TRIDENT;
            case CROSSBOW -> BipedEntityModel.ArmPose.CROSSBOW_CHARGE;
            case SPYGLASS -> BipedEntityModel.ArmPose.SPYGLASS;
            case TOOT_HORN -> BipedEntityModel.ArmPose.TOOT_HORN;
            case BRUSH -> BipedEntityModel.ArmPose.BRUSH;
            case SPEAR -> BipedEntityModel.ArmPose.SPEAR;
            default -> null;
        };
    }

    /** The name tag shows only the robot's own name, and nothing for a robot named "MineBot". */
    @Override
    protected boolean hasLabel(MineBotEntity entity, double squaredDistanceToCamera) {
        return entity.hasRobotName() && super.hasLabel(entity, squaredDistanceToCamera);
    }

    @Override
    protected Text getDisplayName(MineBotEntity entity) {
        return entity.getCustomName();
    }

    @Override
    public Identifier getTexture(MineBotRenderState state) {
        SkinTextures textures = TEXTURES.get(state.skin);
        if (state.evil) {
            return textures.evil();
        }

        return state.connected ? textures.connected() : textures.idle();
    }

    private static Identifier texture(MineBotSkin skin, String state) {
        return MineBotMod.id("textures/entity/minebot/" + skin.id() + "_" + state + ".png");
    }

    private record SkinTextures(Identifier idle, Identifier connected, Identifier evil) {
    }
}
