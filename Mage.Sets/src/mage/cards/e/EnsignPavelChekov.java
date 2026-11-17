package mage.cards.e;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.dynamicvalue.common.DifferentlyNamedPermanentCount;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.hint.common.StrangeNewWorldsHint;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.AbilityWord;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.players.Player;
import mage.target.TargetCard;
import mage.util.CardUtil;

import java.util.UUID;

public final class EnsignPavelChekov extends CardImpl {

    static final DifferentlyNamedPermanentCount LANDS = new DifferentlyNamedPermanentCount(StaticFilters.FILTER_CONTROLLED_PERMANENT_LANDS);

    public EnsignPavelChekov(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.PILOT);
        this.power = new MageInt(1);
        this.toughness = new MageInt(5);

        // Strange new worlds -- When Ensign Chekov enters, each opponent exiles the top X cards of their library, where
        // X is the number of differently named lands you control. For each opponent, choose a land card that player
        // owns exiled this way. You may play the chosen cards for as long as they remain exiled.
        this.addAbility(new EntersBattlefieldTriggeredAbility(new EnsignPavelChekovEffect())
                .setAbilityWord(AbilityWord.STRANGE_NEW_WORLDS).addHint(StrangeNewWorldsHint.instance));
    }

    private EnsignPavelChekov(final EnsignPavelChekov card) {
        super(card);
    }

    @Override
    public EnsignPavelChekov copy() {
        return new EnsignPavelChekov(this);
    }
}

class EnsignPavelChekovEffect extends OneShotEffect {

    EnsignPavelChekovEffect() {
        super(Outcome.PutLandInPlay);
        staticText = "each opponent exiles the top X cards of their library, where X is the number of differently "
                + "named lands you control. For each opponent, choose a land card that player owns exiled this way. "
                + "You may play the chosen cards for as long as they remain exiled";
    }

    private EnsignPavelChekovEffect(final EnsignPavelChekovEffect effect) {
        super(effect);
    }

    @Override
    public EnsignPavelChekovEffect copy() {
        return new EnsignPavelChekovEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        int x = EnsignPavelChekov.LANDS.calculate(game, source, this);
        if (x < 1) {
            return true;
        }
        for (UUID opponentId : game.getOpponents(source.getControllerId())) {
            Player opponent = game.getPlayer(opponentId);
            if (opponent == null) {
                continue;
            }
            Cards exiled = new CardsImpl(opponent.getLibrary().getTopCards(game, x));
            opponent.moveCards(exiled, Zone.EXILED, source, game);
            exiled.retainZone(Zone.EXILED, game);
            if (exiled.count(StaticFilters.FILTER_CARD_LAND, game) == 0) {
                continue;
            }
            TargetCard target = new TargetCard(Zone.EXILED, StaticFilters.FILTER_CARD_LAND);
            target.withNotTarget(true);
            target.withChooseHint("you may play it while it remains exiled");
            controller.choose(Outcome.PutLandInPlay, exiled, target, source, game);
            Card chosen = game.getCard(target.getFirstTarget());
            if (chosen != null) {
                CardUtil.makeCardPlayable(game, source, chosen, false, Duration.Custom, false, controller.getId(), null);
            }
        }
        return true;
    }
}
