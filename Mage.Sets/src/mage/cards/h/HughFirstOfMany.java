package mage.cards.h;

import mage.MageInt;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.continuous.GainAbilityControlledSpellsEffect;
import mage.abilities.keyword.ReplicateAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.common.FilterNonlandCard;

import java.util.UUID;

public final class HughFirstOfMany extends CardImpl {

    private static final FilterNonlandCard filter = new FilterNonlandCard("artifact spells you cast");

    static {
        filter.add(CardType.ARTIFACT.getPredicate());
    }

    public HughFirstOfMany(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT, CardType.CREATURE}, "{1}{W}{U}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.BORG);
        this.power = new MageInt(3);
        this.toughness = new MageInt(4);

        // Artifact spells you cast have replicate {2}{U}.
        this.addAbility(new SimpleStaticAbility(new GainAbilityControlledSpellsEffect(new ReplicateAbility("{2}{U}"), filter)));
    }

    private HughFirstOfMany(final HughFirstOfMany card) {
        super(card);
    }

    @Override
    public HughFirstOfMany copy() {
        return new HughFirstOfMany(this);
    }
}
