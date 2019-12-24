/**
    Copyright (C) <2017> <coolAlias>

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

import dynamicswordskills.DSSCombatEvents;
import dynamicswordskills.client.DSSClientEvents;
import dynamicswordskills.entity.EntityLeapingBlow;
import dynamicswordskills.ref.Config;
import dynamicswordskills.ref.ModSounds;
import dynamicswordskills.util.PlayerUtils;
import dynamicswordskills.util.TargetUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerFlyableFallEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 
 * LEAPING BLOW
 * Activation: Jump while holding block, then attack (shield is not required)
 * Damage: Regular sword damage (without enchantment bonuses), +1 extra damage per skill level
 * Effect: Adds Weakness I for (50 + (10 * level)) ticks
 * Range: Technique travels roughly 3 blocks + 1/2 block per level
 * Area: Approximately (0.5F + (0.25F * level)) radius in a straight line
 * Exhaustion: 2.0F minus 0.1F per level (1.5F at level 5)
 * 
 * Upon landing, all targets directly in front of the player take damage and
 * are weakened temporarily.
 * 
 */
public class LeapingBlow extends SkillActive
{
	/** Activation window for pressing the attack key, set when player initially leaps */
	private int ticksTilFail;

	/** Set to true when activated; set to false upon landing */
	private boolean isActive = false;

	public LeapingBlow(String name) {
		super(name);
	}

	private LeapingBlow(LeapingBlow skill) {
		super(skill);
	}

	@Override
	public LeapingBlow newInstance() {
		return new LeapingBlow(this);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void addInformation(List<String> desc, EntityPlayer player) {
		desc.add(getDamageDisplay(level, true));
		desc.add(getRangeDisplay(3.0F + 0.5F * level));
		desc.add(getAreaDisplay(0.5F + 0.25F * level));
		desc.add(getDurationDisplay(getPotionDuration(player), false));
		desc.add(getExhaustionDisplay(getExhaustion()));
	}

	@Override
	public boolean isActive() {
		return isActive;
	}

	@Override
	public boolean isAnimating() {
		return isActive() || ticksTilFail > 0;
	}

	@Override
	protected float getExhaustion() {
		return 2.0F - (0.1F * level);
	}

	/**
	 * LeapingBlow adds exhaustion after entity is spawned, rather than on initial activation
	 */
	@Override
	protected boolean autoAddExhaustion() {
		return false;
	}

	/** Returns player's base damage (which includes all attribute bonuses) plus 1.0F per level */
	private float getDamage(EntityPlayer player) {
		return (float)(level + player.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue());
	}

	/** Duration of weakness effect; used for tooltip display only */
	private int getPotionDuration(EntityPlayer player) {
		return (50 + (level * 10));
	}

	@Override
	public boolean canUse(EntityPlayer player) {
		return super.canUse(player) && !isActive() && PlayerUtils.isSwordOrProvider(player.getHeldItemMainhand(), this) && !TargetUtils.isInLiquid(player);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canExecute(EntityPlayer player) {
		return ticksTilFail > 0 && !player.onGround && canUse(player);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean isKeyListener(Minecraft mc, KeyBinding key, boolean isLockedOn) {
		if (Config.requiresLockOn() && !isLockedOn) {
			return false;
		}
		return (key == mc.gameSettings.keyBindJump || key == mc.gameSettings.keyBindAttack);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean keyPressed(Minecraft mc, KeyBinding key, EntityPlayer player) {
		if (key == mc.gameSettings.keyBindJump) {
			if (player.onGround && isKeyPressed() && canUse(player)) {
				ticksTilFail = 10;
				if (player.isHandActive()) {
					KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
				}
				return true;
			}
		} else if (canExecute(player) && activate(player)) {
			DSSCombatEvents.setPlayerAttackTime(player); // prevent left-click spam
			return true;
		}
		return false;
	}

	/**
	 * Returns true if either the custom block key or vanilla use item key is currently down
	 */
	@SideOnly(Side.CLIENT)
	protected boolean isKeyPressed() {
		return Minecraft.getMinecraft().gameSettings.keyBindUseItem.isKeyDown();
	}

	@Override
	protected boolean onActivated(World world, EntityPlayer player) {
		isActive = true;
		ticksTilFail = 0;
		return isActive();
	}

	@Override
	protected void onDeactivated(World world, EntityPlayer player) {
		isActive = false;
		ticksTilFail = 0;
	}

	@Override
	public void onUpdate(EntityPlayer player) {
		// Handle on client because onGround is always true on the server
		if (player.worldObj.isRemote) {
			if (isActive() && (player.onGround || TargetUtils.isInLiquid(player))) {
				deactivate(player);
			} else if (ticksTilFail > 0) {
				--ticksTilFail;
			}
		}
	}

	@Override
	public boolean onFall(EntityPlayer player, LivingFallEvent event) {
		onFall(player, event.getDistance());
		return true;
	}

	@Override
	public boolean onCreativeFall(EntityPlayer player, PlayerFlyableFallEvent event) {
		onFall(player, event.getDistance());
		return true;
	}

	private void onFall(EntityPlayer player, float distance) {
		if (isActive() && PlayerUtils.isSwordOrProvider(player.getHeldItemMainhand(), this)) {
			if (player.worldObj.isRemote) {
				if (distance < 1.0F) {
					DSSClientEvents.handlePlayerAttack(Minecraft.getMinecraft());
				} else {
					player.swingArm(EnumHand.MAIN_HAND);
					player.resetCooldown();
				}
			} else if (distance >= 1.0F) {
				// add exhaustion here, now that skill has truly activated:
				player.addExhaustion(getExhaustion());
				EntityLeapingBlow entity = new EntityLeapingBlow(player.worldObj, player).setDamage(getDamage(player)).setLevel(level);
				entity.setHeadingFromThrower(player, player.rotationPitch, player.rotationYaw, 0.0F, entity.getVelocity(), 1.0F);
				player.worldObj.spawnEntityInWorld(entity);
				PlayerUtils.playSoundAtEntity(player.worldObj, player, ModSounds.LEAPING_BLOW, SoundCategory.PLAYERS, 0.4F, 0.5F);
				player.resetCooldown();
			}
		}
		onDeactivated(player.worldObj, player);
	}
}
