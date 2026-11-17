package mage.cards.m;

import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.common.CantBeBeamedUpAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.common.AttachEffect;
import mage.abilities.effects.common.DoIfCostPaid;
import mage.abilities.effects.common.DontUntapInControllersUntapStepEnchantedEffect;
import mage.abilities.effects.common.TapEnchantedEffect;
import mage.abilities.effects.common.continuous.GainAbilityAttachedEffect;
import mage.abilities.keyword.EnchantAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AttachmentType;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.filter.StaticFilters;
import mage.target.TargetPermanent;

import java.util.UUID;

public final class Marooned extends CardImpl {

    public Marooned(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{U}");

        this.subtype.add(SubType.AURA);

        // Enchant artifact or creature
        TargetPermanent auraTarget = new TargetPermanent(StaticFilters.FILTER_PERMANENT_ARTIFACT_OR_CREATURE);
        this.getSpellAbility().addTarget(auraTarget);
        this.getSpellAbility().addEffect(new AttachEffect(Outcome.Detriment));
        this.addAbility(new EnchantAbility(auraTarget));

        // When this Aura enters, you may pay {3}. If you do, tap enchanted permanent.
        this.addAbility(new EntersBattlefieldTriggeredAbility(new DoIfCostPaid(
                new TapEnchantedEffect().setText("tap enchanted permanent"), new GenericManaCost(3)
        )));

        // Enchanted permanent doesn't untap during its controller's untap step and can't be beamed up.
        Ability ability = new SimpleStaticAbility(new DontUntapInControllersUntapStepEnchantedEffect()
                .setText("enchanted permanent doesn't untap during its controller's untap step"));
        ability.addEffect(new GainAbilityAttachedEffect(new CantBeBeamedUpAbility(), AttachmentType.AURA)
                .setText("and can't be beamed up"));
        this.addAbility(ability);
    }

    private Marooned(final Marooned card) {
        super(card);
    }

    @Override
    public Marooned copy() {
        return new Marooned(this);
    }
}
