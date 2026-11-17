package mage.cards.b;

import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldOrAttacksSourceTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.GainAbilityControlledEffect;
import mage.abilities.effects.keyword.AssimilateEffect;
import mage.abilities.keyword.AffinityAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.AffinityType;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetCard;
import mage.target.TargetPermanent;
import mage.util.CardUtil;

import java.util.UUID;

public final class BorgCube extends CardImpl {

    public BorgCube(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{5}{W}{B}");

        this.subtype.add(SubType.SPACECRAFT);

        // Affinity for artifact creatures
        this.addAbility(new AffinityAbility(AffinityType.ARTIFACT_CREATURES));

        // Whenever this Spacecraft enters or attacks, assimilate a creature card exiled with it. If you can't, exile up to one target creature.
        Ability ability = new EntersBattlefieldOrAttacksSourceTriggeredAbility(new BorgCubeEffect());
        ability.addTarget(new TargetPermanent(0, 1, StaticFilters.FILTER_PERMANENT_CREATURE));
        this.addAbility(ability);

        // Station
        this.addAbility(new StationAbility());

        // STATION 12+
        // Artifact creatures you control have flying.
        // 6/6
        this.addAbility(new StationLevelAbility(12)
                .withLevelAbility(new SimpleStaticAbility(new GainAbilityControlledEffect(FlyingAbility.getInstance(),
                        Duration.WhileOnBattlefield, StaticFilters.FILTER_PERMANENTS_ARTIFACT_CREATURE)))
                .withPT(6, 6));
    }

    private BorgCube(final BorgCube card) {
        super(card);
    }

    @Override
    public BorgCube copy() {
        return new BorgCube(this);
    }
}

class BorgCubeEffect extends OneShotEffect {

    BorgCubeEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "assimilate a creature card exiled with it. If you can't, exile up to one target creature";
    }

    private BorgCubeEffect(final BorgCubeEffect effect) {
        super(effect);
    }

    @Override
    public BorgCubeEffect copy() {
        return new BorgCubeEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        UUID exileId = CardUtil.getExileZoneId(game, source);
        ExileZone exile = game.getExile().getExileZone(exileId);
        Cards creatures = new CardsImpl();
        if (exile != null) {
            exile.getCards(game).stream().filter(c -> c.isCreature(game)).forEach(creatures::add);
        }
        if (!creatures.isEmpty()) {
            TargetCard target = new TargetCard(Zone.EXILED, StaticFilters.FILTER_CARD_CREATURE);
            target.withNotTarget(true);
            controller.choose(Outcome.PutCreatureInPlay, creatures, target, source, game);
            Card card = game.getCard(target.getFirstTarget());
            if (card == null) {
                card = creatures.getRandom(game);
            }
            if (AssimilateEffect.assimilate(card, source, game) != null) {
                return true;
            }
        }
        // couldn't assimilate: exile up to one target creature, "with it"
        Permanent creature = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (creature != null) {
            controller.moveCardsToExile(creature, source, game, true, exileId, CardUtil.getSourceName(game, source));
        }
        return true;
    }
}
