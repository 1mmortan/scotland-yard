package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;

public final class MyModelFactory implements ScotlandYard.Factory<Model> {

	@Nonnull
	@Override
	public Model build(GameSetup setup, Player mrX, ImmutableList<Player> detectives) {
		return new MyModel(setup, mrX, detectives);
	}

	private static class MyModel implements Model {
		private final Set<Observer> observers = new HashSet<>();
		private Board.GameState gameState;

		private MyModel(final GameSetup setup, final Player mrX, final ImmutableList<Player> detectives) {
			this.gameState = new MyGameStateFactory().build(setup, mrX, detectives);
		}

		@Nonnull
		@Override
		public Board getCurrentBoard() {
			return gameState;
		}

		@Override
		public void registerObserver(@Nonnull Observer observer) {
			if (observer == null) throw new NullPointerException("Observer cannot be null");
			if (observers.contains(observer))
				throw new IllegalArgumentException("Observer is already registered");
			observers.add(observer);
		}

		@Override
		public void unregisterObserver(@Nonnull Observer observer) {
			if (observer == null) throw new NullPointerException("Observer cannot be null");
			if (!observers.contains(observer))
				throw new IllegalArgumentException("Observer is not registered");
			observers.remove(observer);
		}

		@Nonnull
		@Override
		public ImmutableSet<Observer> getObservers() {
			return ImmutableSet.copyOf(observers);
		}

		@Override
		public void chooseMove(@Nonnull Move move) {
			gameState = gameState.advance(move);
			Observer.Event event = gameState.getWinner().isEmpty() ? Observer.Event.MOVE_MADE : Observer.Event.GAME_OVER;
			observers.forEach(observer -> observer.onModelChanged(gameState, event));
		}
	}
}