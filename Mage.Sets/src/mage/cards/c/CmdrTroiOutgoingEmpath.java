package mage.cards.c;

import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.VigilanceAbility;
import mage.MageInt;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.FilterPlayer;
import mage.filter.StaticFilters;
import mage.filter.predicate.ObjectSourcePlayer;
import mage.filter.predicate.ObjectSourcePlayerPredicate;
import mage.game.Game;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.players.Player;
import mage.target.TargetPlayer;
import mage.util.CardUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class CmdrTroiOutgoingEmpath extends CardImpl {

    private static final FilterPlayer filter = new FilterPlayer("opponent whose turn it is");

    static {
        filter.add(mage.constants.TargetController.OPPONENT.getPlayerPredicate());
        filter.add(CmdrTroiOutgoingEmpathPredicate.instance);
    }

    public CmdrTroiOutgoingEmpath(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.BETAZOID);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ADVISOR);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Vigilance
        this.addAbility(VigilanceAbility.getInstance());

        // {T}: Target opponent whose turn it is may cast a spell from their hand without paying its mana cost. If they
        // do, you copy that spell. You may choose new targets for the copy. If they don't, you draw a card.
        Ability ability = new SimpleActivatedAbility(new CmdrTroiOutgoingEmpathEffect(), new TapSourceCost());
        ability.addTarget(new TargetPlayer(1, 1, false, filter));
        this.addAbility(ability);
    }

    private CmdrTroiOutgoingEmpath(final CmdrTroiOutgoingEmpath card) {
        super(card);
    }

    @Override
    public CmdrTroiOutgoingEmpath copy() {
        return new CmdrTroiOutgoingEmpath(this);
    }
}

enum CmdrTroiOutgoingEmpathPredicate implements ObjectSourcePlayerPredicate<Player> {
    instance;

    @Override
    public boolean apply(ObjectSourcePlayer<Player> input, Game game) {
        return game.isActivePlayer(input.getObject().getId());
    }
}

class CmdrTroiOutgoingEmpathEffect extends OneShotEffect {

    CmdrTroiOutgoingEmpathEffect() {
        super(Outcome.Benefit);
        staticText = "target opponent whose turn it is may cast a spell from their hand without paying its mana cost. "
                + "If they do, you copy that spell. You may choose new targets for the copy. If they don't, you draw a card";
    }

    private CmdrTroiOutgoingEmpathEffect(final CmdrTroiOutgoingEmpathEffect effect) {
        super(effect);
    }

    @Override
    public CmdrTroiOutgoingEmpathEffect copy() {
        return new CmdrTroiOutgoingEmpathEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Player opponent = game.getPlayer(getTargetPointer().getFirst(game, source));
        if (controller == null || opponent == null) {
            return false;
        }
        Set<UUID> before = new HashSet<>();
        for (StackObject so : game.getStack()) {
            before.add(so.getId());
        }
        Cards hand = new CardsImpl(opponent.getHand());
        boolean cast = !hand.isEmpty()
                && opponent.chooseUse(Outcome.PlayForFree, "Cast a spell from your hand without paying its mana cost?", source, game)
                && CardUtil.castSpellWithAttributesForFree(opponent, source, game, hand, StaticFilters.FILTER_CARD_NON_LAND);
        Spell spell = null;
        if (cast) {
            for (StackObject so : game.getStack()) {
                if (so instanceof Spell && !before.contains(so.getId()) && so.isControlledBy(opponent.getId())) {
                    spell = (Spell) so;
                    break;
                }
            }
        }
        if (spell != null) {
            spell.createCopyOnStack(game, source, controller.getId(), true);
        } else {
            controller.drawCards(1, source, game);
        }
        return true;
    }
}
