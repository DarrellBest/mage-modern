package mage.cards.t;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.condition.Condition;
import mage.abilities.condition.common.IsStepCondition;
import mage.abilities.effects.common.combat.ReselectDefenderAttackedByTargetEffect;
import mage.abilities.keyword.FlashAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.target.common.TargetAttackingCreature;

import java.util.UUID;

public final class TuvokChiefTacticalOfficer extends CardImpl {

    private static final Condition condition = new IsStepCondition(PhaseStep.DECLARE_ATTACKERS, false);

    public TuvokChiefTacticalOfficer(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.VULCAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(3);

        // Flash
        this.addAbility(FlashAbility.getInstance());

        // When Tuvok enters during the declare attackers step, choose up to two target attacking creatures. For each
        // of them, you may reselect which player or permanent that creature is attacking.
        Ability ability = new EntersBattlefieldTriggeredAbility(new ReselectDefenderAttackedByTargetEffect(true, true))
                .withTriggerCondition(condition);
        ability.addTarget(new TargetAttackingCreature(0, 2));
        this.addAbility(ability);
    }

    private TuvokChiefTacticalOfficer(final TuvokChiefTacticalOfficer card) {
        super(card);
    }

    @Override
    public TuvokChiefTacticalOfficer copy() {
        return new TuvokChiefTacticalOfficer(this);
    }
}
