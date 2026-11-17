package mage.cards.t;

import mage.abilities.common.SagaAbility;
import mage.abilities.effects.Effects;
import mage.abilities.effects.common.ExileTargetForSourceEffect;
import mage.abilities.effects.common.GainLifeEffect;
import mage.abilities.effects.common.ReturnFromExileForSourceEffect;
import mage.abilities.effects.keyword.InvestigateEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SagaChapter;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.FilterPermanent;
import mage.filter.predicate.Predicates;
import mage.filter.predicate.permanent.TokenPredicate;
import mage.target.TargetPermanent;

import java.util.UUID;

public final class TheMeasureOfAMan extends CardImpl {

    private static final FilterPermanent filter = new FilterPermanent("nontoken artifact or creature");

    static {
        filter.add(Predicates.or(CardType.ARTIFACT.getPredicate(), CardType.CREATURE.getPredicate()));
        filter.add(TokenPredicate.FALSE);
    }

    public TheMeasureOfAMan(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{2}{W}");

        this.subtype.add(SubType.SAGA);

        // (As this Saga enters and after your draw step, add a lore counter. Sacrifice after III.)
        SagaAbility sagaAbility = new SagaAbility(this);

        // I -- Exile up to one target nontoken artifact or creature.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_I,
                new Effects(new ExileTargetForSourceEffect()), new TargetPermanent(0, 1, filter));

        // II -- Investigate.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_II, new InvestigateEffect());

        // III -- Return the exiled card to the battlefield under its owner's control. You gain 2 life.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_III,
                new ReturnFromExileForSourceEffect(Zone.BATTLEFIELD), new GainLifeEffect(2));

        this.addAbility(sagaAbility);
    }

    private TheMeasureOfAMan(final TheMeasureOfAMan card) {
        super(card);
    }

    @Override
    public TheMeasureOfAMan copy() {
        return new TheMeasureOfAMan(this);
    }
}
