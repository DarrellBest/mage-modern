package mage.cards.c;

import mage.abilities.Ability;
import mage.abilities.common.AttacksTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.condition.Condition;
import mage.abilities.effects.common.CastSourceTriggeredAbility;
import mage.abilities.effects.common.CopySourceSpellEffect;
import mage.abilities.effects.common.combat.CantBeBlockedSourceEffect;
import mage.abilities.effects.common.continuous.GainAbilityAttachedEffect;
import mage.abilities.keyword.EquipAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AttachmentType;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.SubType;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.util.UUID;

public final class Combadge extends CardImpl {

    public Combadge(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{3}");

        this.subtype.add(SubType.COMMUNICATOR);
        this.subtype.add(SubType.EQUIPMENT);

        // When you cast this spell, copy it.
        this.addAbility(new CastSourceTriggeredAbility(new CopySourceSpellEffect().setText("copy it")));

        // Equipped creature has "Whenever this creature attacks while you control another creature with a
        // Communicator attached to it, this creature can't be blocked this turn."
        Ability attack = new AttacksTriggeredAbility(new CantBeBlockedSourceEffect(Duration.EndOfTurn)
                .setText("this creature can't be blocked this turn"))
                .withInterveningIf(CombadgeCondition.instance)
                .setTriggerPhrase("Whenever this creature attacks while you control another creature with a Communicator attached to it, ");
        this.addAbility(new SimpleStaticAbility(new GainAbilityAttachedEffect(attack, AttachmentType.EQUIPMENT)));

        // Equip {2}
        this.addAbility(new EquipAbility(2));
    }

    private Combadge(final Combadge card) {
        super(card);
    }

    @Override
    public Combadge copy() {
        return new Combadge(this);
    }
}

enum CombadgeCondition implements Condition {
    instance;

    @Override
    public boolean apply(Game game, Ability source) {
        for (Permanent creature : game.getBattlefield().getAllActivePermanents(
                mage.filter.StaticFilters.FILTER_PERMANENT_CREATURE, source.getControllerId(), game)) {
            if (creature.getId().equals(source.getSourceId())) {
                continue;
            }
            for (UUID attachmentId : creature.getAttachments()) {
                Permanent attachment = game.getPermanent(attachmentId);
                if (attachment != null && attachment.hasSubtype(SubType.COMMUNICATOR, game)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "you control another creature with a Communicator attached to it";
    }
}
