package mage.cards.g;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.DealsCombatDamageToAPlayerTriggeredAbility;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.dynamicvalue.common.SavedDamageValue;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.effects.common.UntapTargetEffect;
import mage.abilities.effects.common.continuous.GainAbilityTargetEffect;
import mage.abilities.effects.common.continuous.GainControlTargetEffect;
import mage.abilities.keyword.HasteAbility;
import mage.abilities.keyword.TrampleAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.permanent.token.TreasureToken;
import mage.target.common.TargetOpponentsCreaturePermanent;

import java.util.UUID;

public final class GrilkaWidowedWarrior extends CardImpl {

    public GrilkaWidowedWarrior(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{5}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.KLINGON);
        this.subtype.add(SubType.WARRIOR);
        this.power = new MageInt(5);
        this.toughness = new MageInt(5);

        // When Grilka enters, gain control of target creature an opponent controls until end of turn. Untap it. Until
        // end of turn, it gains trample, haste, and "Whenever this creature deals combat damage to a player, create
        // that many tapped Treasure tokens."
        Ability ability = new EntersBattlefieldTriggeredAbility(new GainControlTargetEffect(Duration.EndOfTurn));
        ability.addEffect(new UntapTargetEffect().setText("Untap it"));
        ability.addEffect(new GainAbilityTargetEffect(TrampleAbility.getInstance(), Duration.EndOfTurn)
                .setText("Until end of turn, it gains trample"));
        ability.addEffect(new GainAbilityTargetEffect(HasteAbility.getInstance(), Duration.EndOfTurn)
                .setText(", haste"));
        ability.addEffect(new GainAbilityTargetEffect(new DealsCombatDamageToAPlayerTriggeredAbility(
                new CreateTokenEffect(new TreasureToken(), SavedDamageValue.MANY, true, false)
        ), Duration.EndOfTurn).setText(", and \"Whenever this creature deals combat damage to a player, "
                + "create that many tapped Treasure tokens.\""));
        ability.addTarget(new TargetOpponentsCreaturePermanent());
        this.addAbility(ability);
    }

    private GrilkaWidowedWarrior(final GrilkaWidowedWarrior card) {
        super(card);
    }

    @Override
    public GrilkaWidowedWarrior copy() {
        return new GrilkaWidowedWarrior(this);
    }
}
