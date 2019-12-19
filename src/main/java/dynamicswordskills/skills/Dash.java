/**
    Copyright (C) <2016> <coolAlias>

    This file is part of coolAlias' Dynamic Sword Skills Minecraft Mod; as such,
    you can redistribute it and/or modify it under the terms of the GNU
    General Public License as published by the Free Software Foundation,
    either version 3 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package dynamicswordskills.skills;

import java.util.List;

import dynamicswordskills.DynamicSwordSkills;
import dynamicswordskills.client.DSSKeyHandler;
import dynamicswordskills.entity.DSSPlayerInfo;
import dynamicswordskills.network.PacketDispatcher;
import dynamicswordskills.network.server.DashImpactPacket;
import dynamicswordskills.ref.Config;
import dynamicswordskills.ref.ModSounds;
import dynamicswordskills.util.PlayerUtils;
import dynamicswordskills.util.TargetUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketEntityVelocity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import swordskillsapi.api.item.IDashItem;

/**
 * 
 * Attacking while blocking and locked on to a target will execute a bash attack.
 * The player charges into the target, inflicting damage and knocking the target back.
 * 
 * Range: 3 blocks plus 1 block per level
 * Damage: up to 2 plus 1 per level at max range
 * Knockback Strength: 0.3F per level plus an additional 0.1F per block traveled beyond the minimum (capped at 2.0F)
 * Exhaustion: Light [1.0F - (level * 0.05F)]
 * Special: Must be at least 2 blocks away from target when skill is activated to
 * 			inflict damage, minus 0.2F per level (down to 1 block at level 5)
 * Special: Effects that increase player speed increase the effective range, damage, and knockback.
 * 
 */
public class Dash extends SkillActive
{
	/** Player's base movement speed */
	public static final double BASE_MOVE = 0.10000000149011612D;

	/** True when Slam is used and while the player is in motion towards the target */
	private boolean isActive = false;

	/** Number of ticks since activation */
	private int activeTime;

	/**
	 * The dash trajectory is set once when activated, to prevent the vec3 coordinates from
	 * shrinking as the player nears the target; as a bonus, Dash is no longer 'homing'
	 */
	@SideOnly(Side.CLIENT)
	private Vec3d trajectory;

	/** Player's starting position is used to determine actual distance traveled upon impact */
	private Vec3d initialPosition;

	/** Target acquired from ILockOnTarget skill; set to the entity hit upon impact */
	private Entity target;

	/** Impact timer used to make player immune to damage from struck target only, vs. setting hurtResistantTime */
	private int impactTime;

	public Dash(String name) {
		super(name);
	}

	private Dash(Dash skill) {
		super(skill);
	}

	@Override
	public Dash newInstance() {
		return new Dash(this);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void addInformation(List<String> desc, EntityPlayer player) {
		desc.add(getDamageDisplay(getDamage(), false));
		desc.add(new TextComponentTranslation(getInfoString("info", 1), String.format("%.1f", getKnockback())).getUnformattedText());
		desc.add(new TextComponentTranslation(getRangeDisplay(getRange())).getUnformattedText());
		desc.add(new TextComponentTranslation(getInfoString("info", 2), String.format("%.1f", getMinDistance())).getUnformattedText());
		desc.add(new TextComponentTranslation(getExhaustionDisplay(getExhaustion())).getUnformattedText());
	}

	@Override
	public boolean isActive() {
		return isActive || impactTime > 0;
	}

	/** Maximum active time in case player is unable to move an appropriate amount of distance */
	protected int getMaxActiveTime() {
		return 12 + level;
	}

	/** Number of ticks the player will not be able to block or use an item after impact */
	private int getBlockCooldown() {
		return (30 - (2 * level));
	}

	@Override
	protected float getExhaustion() {
		return 1.0F - (0.05F * level);
	}

	/** Damage is base damage plus one per level */
	private int getDamage() {
		return (2 + level);
	}

	/** Returns base knockback strength, not accounting for distance traveled */
	private float getKnockback() {
		return 0.3F * level;
	}

	/** Range increases by 1 block per level */
	private double getRange() {
		return (3.0D + level);
	}

	/** Minimum distance the player must cover before the dash is effective */
	private double getMinDistance() {
		return 2.0D - (0.2D * level);
	}

	@Override
	public boolean canUse(EntityPlayer player) {
		boolean flag = PlayerUtils.isBlocking(player);
		for (EnumHand hand : EnumHand.values()) {
			if (canItemDash(player, hand)) {
				flag = true;
				break;
			}
		}
		return (flag && super.canUse(player) && !isActive());
	}

	/**
	 * Returns true if the item held in the given hand is an {@link IDashItem}
	 * and {@link IDashItem#canDash(ItemStack, EntityPlayer, EnumHand) IDashItem#canDash} returns true.
	 */
	private boolean canItemDash(EntityPlayer player, EnumHand hand) {
		ItemStack stack = player.getHeldItem(hand);
		if (stack != null && stack.getItem() instanceof IDashItem) {
			return ((IDashItem) stack.getItem()).canDash(stack, player, hand);
		}
		return false;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canExecute(EntityPlayer player) {
		return player.onGround && canUse(player);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean isKeyListener(Minecraft mc, KeyBinding key) {
		return (key == DSSKeyHandler.keys[DSSKeyHandler.KEY_ATTACK].getKey() || (Config.allowVanillaControls() && key == mc.gameSettings.keyBindAttack));
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean keyPressed(Minecraft mc, KeyBinding key, EntityPlayer player) {
		return canExecute(player) && activate(player);
	}

	@Override
	protected boolean onActivated(World world, EntityPlayer player) {
		isActive = true;
		activeTime = 0;
		initialPosition = new Vec3d(player.posX, player.posY, player.posZ);
		ILockOnTarget skill = DSSPlayerInfo.get(player).getTargetingSkill();
		if (skill != null && skill.isLockedOn()) {
			target = skill.getCurrentTarget();
		} else {
			target = TargetUtils.acquireLookTarget(player, (int) getRange(), getRange(), true, TargetUtils.getDefaultSelectors());
		}
		if (target != null && world.isRemote) {
			double d0 = (target.posX - player.posX);
			double d1 = (target.posY + (double)(target.height / 3.0F) - player.posY);
			double d2 = (target.posZ - player.posZ);
			trajectory = new Vec3d(d0, d1, d2).normalize();
		}
		return isActive();
	}

	@Override
	protected void onDeactivated(World world, EntityPlayer player) {
		if (player.worldObj.isRemote) {
			trajectory = null;
		}
		initialPosition = null;
		impactTime = 0; // no longer active, target will be set to null from setNotDashing
		setNotDashing(); // sets all remaining fields to 0 or null
	}

	@Override
	public void onUpdate(EntityPlayer player) {
		if (impactTime > 0) {
			--impactTime;
			if (impactTime == 0) {
				target = null;
			}
		}
		// don't use isActive() method, as that also returns true after impact
		if (isActive) {
			// Only check for impact on the client, as the server is not reliable for this step
			// If a collision is detected, DashImpactPacket is sent to conclude the server-side
			if (!PlayerUtils.isBlocking(player)) {
				setNotDashing();
			} else if (player.worldObj.isRemote) {
				RayTraceResult result = TargetUtils.checkForImpact(player.worldObj, player, player, 0.5D, false);
				if (result != null || player.isCollidedHorizontally) {
					PacketDispatcher.sendToServer(new DashImpactPacket(player, result));
					player.resetCooldown(); // player effectively made an attack
					// Force player to stop blocking upon impact
					DSSPlayerInfo.get(player).setUseItemCooldown(getBlockCooldown());
					KeyBinding.setKeyBindState(Minecraft.getMinecraft().gameSettings.keyBindUseItem.getKeyCode(), false);
					KeyBinding.setKeyBindState(DSSKeyHandler.keys[DSSKeyHandler.KEY_BLOCK].getKeyCode(), false);
					impactTime = 5;
					if (result != null && result.typeOfHit == RayTraceResult.Type.ENTITY) {
						target = result.entityHit;
					}
					double d = Math.sqrt((player.motionX * player.motionX) + (player.motionZ * player.motionZ));
					player.setVelocity(-player.motionX * d, 0.15D * d, -player.motionZ * d);
					trajectory = null; // set to null so player doesn't keep moving forward
					setNotDashing();
				}
			}
		}
		// Update active time if still active
		if (isActive) {
			++activeTime;
			if (activeTime > getMaxActiveTime()) {
				if (!player.worldObj.isRemote) {
					deactivate(player);
				}
			}
		}
	}

	/**
	 * Called on the server from {@link DashImpactPacket} to process the impact data from the client
	 * @param player	Player's motionX and motionZ have been set by the packet, so the values may be used
	 * @param mop	Null assumes a block was hit (none of the block data is needed, so it is not sent),
	 * 				or a valid MovingObjectPosition for the entity hit
	 */
	public void onImpact(World world, EntityPlayer player, RayTraceResult result) {
		if (result != null && result.typeOfHit == RayTraceResult.Type.ENTITY) {
			target = result.entityHit;
			double distance = target.getDistance(initialPosition.xCoord, initialPosition.yCoord, initialPosition.zCoord);
			// Subtract hitbox modifier when comparing min distance to avoid hitting enemies right in front of the player
			double bbMod = (target.width / 2.0F) + (player.width / 2.0F);
			double speed = player.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
			double sf = (1.0D + (speed - BASE_MOVE)); // speed factor
			if (player.isInWater() || player.isInLava()) {
				sf *= 0.3D;
			}
			if (speed > 0.075D && (distance - bbMod) > getMinDistance() && distance < (getRange() + 1.0D) && player.getDistanceSqToEntity(target) < 6.0D) {
				float dmg = (float)(sf * (float)getDamage() * distance / getRange());
				impactTime = 5; // time player will be immune to damage from the target entity
				target.attackEntityFrom(DamageSource.causePlayerDamage(player), dmg);
				if (target instanceof EntityLivingBase) {
					float db = 0.1F * (float)(distance - getMinDistance());
					float k = (float)sf * Math.min(db + getKnockback(), 2.0F);
					TargetUtils.knockTargetBack((EntityLivingBase) target, player, k);
				}
				if (target instanceof EntityPlayerMP && !player.worldObj.isRemote) {
					((EntityPlayerMP) target).connection.sendPacket(new SPacketEntityVelocity(target));
				}
			}
		}
		DSSPlayerInfo.get(player).setUseItemCooldown(getBlockCooldown());
		PlayerUtils.playSoundAtEntity(player.worldObj, player, ModSounds.SLAM, SoundCategory.PLAYERS, 0.4F, 0.5F);
		setNotDashing();
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean isAnimating() {
		return isActive; // don't continue render tick updates after impact
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean onRenderTick(EntityPlayer player, float partialTickTime) {
		if (initialPosition == null || player.getDistance(initialPosition.xCoord, initialPosition.yCoord, initialPosition.zCoord) > getRange()) {
			player.addVelocity(-player.motionX * 0.5D, 0.0D, -player.motionZ * 0.5D);
			trajectory = null;
			if (isActive) {
				deactivate(player);
			}
		}
		if (target instanceof EntityLivingBase && trajectory != null) {
			double speed = player.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
			double fps = (DynamicSwordSkills.BASE_FPS / (float) Minecraft.getDebugFPS()); 
			// Bonus is roughly equivalent to sprinting plus an additional amount per level
			double bonus = 1.3D + (0.1D * level);
			double d = speed * bonus * fps;
			if (player.isInWater() || player.isInLava()) {
				d *= 0.3D;
			}
			player.addVelocity(trajectory.xCoord * d, 0.0D, trajectory.zCoord * d);
		}
		return false; // this skill doesn't need to control the camera
	}

	@Override
	public boolean onBeingAttacked(EntityPlayer player, DamageSource source) {
		if (impactTime > 0 && source.getEntity() == target) {
			return true;
		} else if (source.damageType.equals("mob") && source.getEntity() != null && player.getDistanceSqToEntity(source.getEntity()) < 6.0D) {
			return true; // stop stupid zombies from hitting player right before impact
		}
		return false;
	}

	/**
	 * After calling this method, {@link #isAnimating()} will always return false;
	 * {@link #isActive()} will return false if no entity was impacted, otherwise it
	 * will still be true for {@link #impactTime} ticks to prevent damage from the {@link #target}. 
	 */
	private void setNotDashing() {
		isActive = false;
		if (!isActive()) {
			target = null;
		}
	}
}
