package harmonised.pmmo.events;

import harmonised.pmmo.config.AutoValues;
import harmonised.pmmo.config.Config;
import harmonised.pmmo.config.JType;
import harmonised.pmmo.config.JsonConfig;
import harmonised.pmmo.network.MessageDoubleTranslation;
import harmonised.pmmo.network.NetworkHandler;
import harmonised.pmmo.party.Party;
import harmonised.pmmo.party.PartyMemberInfo;
import harmonised.pmmo.pmmo_saved_data.PmmoSavedData;
import harmonised.pmmo.skills.Skill;
import harmonised.pmmo.util.XP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDamageEvent;

import java.util.HashMap;
import java.util.Map;

public class DamageHandler
{
    public static void handleDamage( LivingDamageEvent event )
    {
        if( !(event.getEntity() instanceof FakePlayer) )
        {
            float damage = event.getAmount();
            float startDmg = damage;
            LivingEntity target = event.getEntityLiving();
            Entity source = event.getSource().getTrueSource();
            if( target instanceof ServerPlayerEntity )		//player hurt
            {
                ServerPlayerEntity player = (ServerPlayerEntity) target;
                double agilityXp = 0;
                double enduranceXp;
                boolean hideEndurance = false;
///////////////////////////////////////////////////////////////////////PARTY//////////////////////////////////////////////////////////////////////////////////////////
                if( source instanceof ServerPlayerEntity && !(source instanceof FakePlayer) )
                {
                    ServerPlayerEntity sourcePlayer = (ServerPlayerEntity) source;
                    Party party = PmmoSavedData.get().getParty( player.getUniqueID() );
                    if( party != null )
                    {
                        PartyMemberInfo sourceMemberInfo = party.getMemberInfo( sourcePlayer.getUniqueID() );
                        double friendlyFireMultiplier = Config.forgeConfig.partyFriendlyFireAmount.get() / 100D;

                        if( sourceMemberInfo != null )
                            damage *= friendlyFireMultiplier;
                        if( damage == 0 )
                        {
                            event.setCanceled( true );
                            return;
                        }
                    }
                }
///////////////////////////////////////////////////////////////////////ENDURANCE//////////////////////////////////////////////////////////////////////////////////////////
                int enduranceLevel = Skill.ENDURANCE.getLevel( player );
                double endurancePerLevel = Config.forgeConfig.endurancePerLevel.get();
                double maxEndurance = Config.forgeConfig.maxEndurance.get();
                double endurePercent = (enduranceLevel * endurancePerLevel);
                if( endurePercent > maxEndurance )
                    endurePercent = maxEndurance;
                endurePercent /= 100;

                double endured = damage * endurePercent;
                if( endured < 0 )
                    endured = 0;

                damage -= endured;

                enduranceXp = ( damage * 5 ) + ( endured * 7.5 );
///////////////////////////////////////////////////////////////////////FALL//////////////////////////////////////////////////////////////////////////////////////////////
                if( event.getSource().getDamageType().equals( "fall" ) )
                {
                    double award;
//					float savedExtra = 0;
                    int agilityLevel = Skill.AGILITY.getLevel( player );
                    int saved = 0;

                    double maxFallSaveChance = Config.forgeConfig.maxFallSaveChance.get();
                    double saveChancePerLevel = Config.forgeConfig.saveChancePerLevel.get() / 100;

                    double chance = agilityLevel * saveChancePerLevel;
                    if( chance > maxFallSaveChance )
                        chance = maxFallSaveChance;
                    for( int i = 0; i < damage; i++ )
                    {
                        if( Math.ceil( Math.random() * 100 ) <= chance )
                        {
                            saved++;
                        }
                    }
                    damage -= saved;

                    if( damage <= 0 )
                        event.setCanceled( true );

                    if( saved != 0 && player.getHealth() > damage )
                        player.sendStatusMessage( new TranslationTextComponent( "pmmo.savedFall", saved ), true );

                    award = saved * 5;

                    agilityXp = award;
                }

                event.setAmount( damage );

                if( player.getHealth() > damage )
                {
                    if( agilityXp > 0 )
                        hideEndurance = true;

                    if( event.getSource().getTrueSource() != null )
                        XP.awardXp( player, Skill.ENDURANCE, event.getSource().getTrueSource().getDisplayName().getString(), enduranceXp, hideEndurance, false, false );
                    else
                        XP.awardXp( player, Skill.ENDURANCE, event.getSource().getDamageType(), enduranceXp, hideEndurance, false, false );

                    if( agilityXp > 0 )
                        XP.awardXp( player, Skill.AGILITY, "surviving " + startDmg + " fall damage", agilityXp, false, false, false );
                }
            }

///////////////////////////////////////Attacking////////////////////////////////////////////////////////////

            if ( target instanceof LivingEntity && event.getSource().getTrueSource() instanceof ServerPlayerEntity )
            {
//				IAttributeInstance test = target.getAttribute( Attributes.GENERIC_MOVEMENT_SPEED );
//				if( !(target instanceof AnimalEntity) )
//					System.out.println( test.getValue() + " " + test.getBaseValue() );

                ServerPlayerEntity player = (ServerPlayerEntity) event.getSource().getTrueSource();

                if( player.getHeldItemMainhand().getItem().equals( Items.DEBUG_STICK ) )
                    player.sendStatusMessage( new StringTextComponent( target.getEntityString() ), false );

                if( XP.isPlayerSurvival( player ) )
                {
                    ItemStack itemStack = player.getHeldItemMainhand();
                    ResourceLocation resLoc = player.getHeldItemMainhand().getItem().getRegistryName();
                    Map<String, Double> weaponReq = XP.getJsonMap( resLoc, JType.REQ_WEAPON );
                    if( Config.forgeConfig.autoGenerateWeaponReqDynamicallyEnabled.get() )
                        weaponReq.put( Skill.COMBAT.toString(), Math.max( weaponReq.getOrDefault( Skill.COMBAT.toString(), 0D ), AutoValues.getWeaponReqFromStack( itemStack ) ) );
                    int weaponGap = XP.getSkillReqGap( player, weaponReq );
                    int enchantGap = XP.getSkillReqGap( player, XP.getEnchantsUseReq( player.getHeldItemMainhand() ) );
                    int gap = Math.max( weaponGap, enchantGap );
                    if( gap > 0 )
                    {
                        if( enchantGap < gap )
                            NetworkHandler.sendToPlayer( new MessageDoubleTranslation( "pmmo.notSkilledEnoughToUseAsWeapon", player.getHeldItemMainhand().getTranslationKey(), "", true, 2 ), (ServerPlayerEntity) player );
                        if( Config.forgeConfig.strictReqWeapon.get() )
                        {
                            event.setCanceled( true );
                            return;
                        }
                    }

                    int killGap = 0;

                    if( target.getEntityString() != null )
                    {
                        killGap = XP.getSkillReqGap( player, XP.getResLoc( target.getEntityString() ), JType.REQ_KILL );
                        if( killGap > 0 )
                        {
                            player.sendStatusMessage( new TranslationTextComponent( "pmmo.notSkilledEnoughToDamage", new TranslationTextComponent( target.getType().getTranslationKey() ) ).setStyle( XP.textStyle.get( "red" ) ), true );
                            player.sendStatusMessage( new TranslationTextComponent( "pmmo.notSkilledEnoughToDamage", new TranslationTextComponent( target.getType().getTranslationKey() ) ).setStyle( XP.textStyle.get( "red" ) ), false );

                            for( Map.Entry<String, Double> entry : JsonConfig.data.get( JType.REQ_KILL ).get( target.getEntityString() ).entrySet() )
                            {
                                int level = Skill.getSkill( entry.getKey() ).getLevel( player );

                                if( level < entry.getValue() )
                                    player.sendStatusMessage( new TranslationTextComponent( "pmmo.levelDisplay", new TranslationTextComponent( "pmmo." + entry.getKey() ), "" + (int) Math.floor( entry.getValue() ) ).setStyle( XP.textStyle.get( "red" ) ), false );
                                else
                                    player.sendStatusMessage( new TranslationTextComponent( "pmmo.levelDisplay", new TranslationTextComponent( "pmmo." + entry.getKey() ), "" + (int) Math.floor( entry.getValue() ) ).setStyle( XP.textStyle.get( "green" ) ), false );
                            }
                        }
                        if( Config.forgeConfig.strictReqKill.get() )
                        {
                            event.setCanceled( true );
                            return;
                        }
                    }

                    event.setAmount( event.getAmount() / (weaponGap + 1) / (killGap + 1) );
                    damage = event.getAmount();

                    float amount = 0;
                    float playerHealth = player.getHealth();
                    float targetHealth = target.getHealth();
                    float targetMaxHealth = target.getMaxHealth();
                    float lowHpBonus = 1.0f;
                    Map<String, Double> itemSpecific = JsonConfig.data.get( JType.ITEM_SPECIFIC ).getOrDefault( resLoc.toString(), new HashMap<>() );
                    Skill skill = event.getSource().damageType.equals( "arrow" ) ? Skill.ARCHERY : Skill.COMBAT;

                    if( itemSpecific.getOrDefault( "meleeWeapon", 0D ) != 0 )
                        skill = Skill.COMBAT;
                    else if( itemSpecific.getOrDefault( "archeryWeapon", 0D ) != 0 )
                        skill = Skill.ARCHERY;
                    else if( itemSpecific.getOrDefault( "magicWeapon", 0D ) != 0 )
                        skill = Skill.MAGIC;

                    if( damage > targetHealth )		//no overkill xp
                        damage = targetHealth;

                    amount += damage * 3;

                    if ( startDmg >= targetHealth )	//kill reduce xp
                        amount /= 2;

                    if( startDmg >= targetMaxHealth )	//max hp kill reduce xp
                        amount /= 1.5;

//					player.setHealth( 1f );

                    if( target instanceof AnimalEntity)		//reduce xp if passive mob
                        amount /= 2;
                    else if( playerHealth <= 10 )				//if aggresive mob and low hp
                    {
                        lowHpBonus += ( 11 - playerHealth ) / 5;
                        if( playerHealth <= 2 )
                            lowHpBonus += 1;
                    }

                    if( skill.equals( Skill.ARCHERY ) || skill.equals( Skill.MAGIC ) )
                    {
                        double distance = event.getEntity().getDistance( player );
                        if( distance > 16 )
                            distance -= 16;
                        else
                            distance = 0;

                        amount += ( Math.pow( distance, 1.25 ) * ( damage / target.getMaxHealth() ) * ( damage >= targetMaxHealth ? 1.5 : 1 ) );	//add distance xp
                        amount *= lowHpBonus;

                        XP.awardXp( player, skill, player.getHeldItemMainhand().getDisplayName().toString(), amount, false, false, false );
                    }
                    else
                    {
                        amount *= lowHpBonus;
                        XP.awardXp( player, skill, player.getHeldItemMainhand().getDisplayName().toString(), amount, false, false, false );
                    }

                    if( weaponGap > 0 )
                        player.getHeldItemMainhand().damageItem( weaponGap - 1, player, (a) -> a.sendBreakAnimation(Hand.MAIN_HAND ) );
                }
            }
        }
    }
}