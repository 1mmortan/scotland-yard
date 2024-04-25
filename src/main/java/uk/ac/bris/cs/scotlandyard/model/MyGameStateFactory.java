package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.sql.Array;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.Piece.*;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.*;

public final class MyGameStateFactory implements Factory<GameState> {
	private final class MyGameState implements GameState {
		private GameSetup setup;
		private ImmutableSet<Piece> remaining;
		private static ImmutableList<LogEntry> log;
		private Player mrX;
		private List<Player> detectives;
		private ImmutableSet<Move> moves;
		private ImmutableSet<Piece> winner;


		private MyGameState(
				final GameSetup setup,
				final ImmutableSet<Piece> remaining,
				final ImmutableList<LogEntry> log,
				final Player mrX,
				final List<Player> detectives) {
			this.setup = setup;
			this.remaining = remaining;
			this.log = log;
			this.mrX = mrX;
			this.detectives = detectives;

			// Validation checks for MrX and detectives
			if (mrX.isDetective()) throw new IllegalArgumentException("MrX cannot be a detective");
			if (detectives.isEmpty()) throw new IllegalArgumentException("There must be at least one detective");

			Set<Integer> detectiveLocations = detectives.stream().map(Player::location).collect(Collectors.toSet());
			if (detectiveLocations.size() != detectives.size()) {
				throw new IllegalArgumentException("Detectives cannot be on the same location");
			}

			Set<Piece> detectivePieces = detectives.stream().map(Player::piece).collect(Collectors.toSet());
			if (detectivePieces.size() != detectives.size()) {
				throw new IllegalArgumentException("There cannot be duplicate detectives");
			}

			if (setup.moves.isEmpty())
				throw new IllegalArgumentException("There must be at least one move in the setup");

			for (Player detective : detectives) {
				if (detective.tickets().getOrDefault(Ticket.DOUBLE, 0) > 0) {
					throw new IllegalArgumentException("Detectives aren't allowed to have double tickets");
				}
				if (detective.tickets().getOrDefault(Ticket.SECRET, 0) > 0) {
					throw new IllegalArgumentException("Detectives aren't allowed to have secret tickets");
				}
			}
		}

		@Nonnull
		@Override
		public GameSetup getSetup() {
			return setup;
		}

		@Nonnull
		@Override
		public ImmutableList<LogEntry> getMrXTravelLog() {
			return log;
		}

		@Nonnull
		@Override
		public Optional<Integer> getDetectiveLocation(Piece.Detective detective) {
			return detectives.stream()
					.filter(player -> player.piece() == detective)
					.map(Player::location)
					.findFirst();
		}

		@Nonnull
		@Override
		public ImmutableSet<Piece> getPlayers() {
			return Stream.concat(Stream.of(mrX.piece()), detectives.stream().map(Player::piece))
					.collect(ImmutableSet.toImmutableSet());
		}

		@Nonnull
		@Override
		public Optional<TicketBoard> getPlayerTickets(Piece piece) {
			if (piece.isMrX()) {
				return Optional.of(new TicketBoard() {
					@Override
					public int getCount(@Nonnull ScotlandYard.Ticket ticket) {
						return mrX.tickets().getOrDefault(ticket, 0);
					}
				});
			}

			for (Player player : detectives) {
				if (player.piece().equals(piece)) {
					return Optional.of(new TicketBoard() {
						@Override
						public int getCount(@Nonnull ScotlandYard.Ticket ticket) {
							return player.tickets().getOrDefault(ticket, 0);
						}
					});
				}
			}

			return Optional.empty();
		}

		@Nonnull
		@Override
		public ImmutableSet<Piece> getWinner() {
			Set<Piece> detectivesPiece = detectives.stream()
					.map(Player::piece)
					.collect(Collectors.toSet());

			Set<Move> mrXMove = new HashSet<>();
			mrXMove.addAll(makeSingleMoves(setup, detectives, mrX, mrX.location()));
			mrXMove.addAll(makeDoubleMoves(setup, detectives, mrX, mrX.location()));

			Set<Move.SingleMove> detectivesMove = detectives.stream()
					.flatMap(player -> makeSingleMoves(setup, detectives, player, player.location()).stream())
					.collect(Collectors.toSet());

			Set<Integer> mrxDestination = new HashSet<>(setup.graph.adjacentNodes(mrX.location()));

			Set<Integer> detectiveLocation = detectives.stream()
					.map(Player::location)
					.collect(Collectors.toSet());

			List<Piece> tmpRemaining = new ArrayList<>(remaining);

			if (log.size() == setup.moves.size() && tmpRemaining.contains(mrX.piece())) {
				return ImmutableSet.of(mrX.piece());
			}

			if (mrxDestination.size() == detectiveLocation.size() && detectiveLocation.containsAll(mrxDestination)) {
				return ImmutableSet.copyOf(detectivesPiece);
			}

			if (detectivesMove.isEmpty()) {
				return detectiveLocation.contains(mrX.location()) ? ImmutableSet.copyOf(detectivesPiece) : ImmutableSet.of(mrX.piece());
			}

			for (Player player : detectives) {
				if (player.location() == mrX.location()) {
					return ImmutableSet.copyOf(detectivesPiece);
				}
			}

			if (mrXMove.isEmpty() && tmpRemaining.get(0) == mrX.piece()) {
				for (Player player : detectives) {
					if (player.location() == mrX.location()) {
						return ImmutableSet.copyOf(detectivesPiece);
					} else {
						return ImmutableSet.of(mrX.piece());
					}
				}
			}

			return ImmutableSet.of();
		}

		private ImmutableSet<Piece> determineWinner() {
			// Mr. X wins if all moves are consumed, and he hasn't been caught
			if (log.size() == setup.moves.size() && !detectives.stream().anyMatch(d -> d.location() == mrX.location())) {
				return ImmutableSet.of(mrX.piece());
			}

			// Detectives win if Mr. X is caught (i.e., a detective is on the same location as Mr. X)
			if (detectives.stream().anyMatch(d -> d.location() == mrX.location())) {
				return detectives.stream().map(Player::piece).collect(ImmutableSet.toImmutableSet());
			}

			// Detectives win if Mr. X has no valid moves left
			if (getAvailableMovesForMrX().isEmpty() && remaining.contains(mrX.piece())) {
				return detectives.stream().map(Player::piece)	.collect(ImmutableSet.toImmutableSet());
			}

			// Detectives win if Mr. X is surrounded and cannot move to any adjacent location
			Set<Integer> detectiveLocations = detectives.stream().map(Player::location).collect(Collectors.toSet());
			Set<Integer> possibleDestinations = setup.graph.adjacentNodes(mrX.location());
			if (detectiveLocations.containsAll(possibleDestinations)) {
				return detectives.stream().map(Player::piece).collect(ImmutableSet.toImmutableSet());
			}

			// Otherwise, the game is not over yet
			return ImmutableSet.of();
		}
		private Set<Move> getAvailableMovesForMrX() {
			Set<Move> availableMoves = new HashSet<>();

			// Generate single and secret moves for Mr. X
			availableMoves.addAll(makeSingleMoves(setup, detectives, mrX, mrX.location()));

			// Generate double moves for Mr. X if he has a DOUBLE ticket and enough moves left
			if (mrX.has(Ticket.DOUBLE) && (setup.moves.size() - log.size() >= 2)) {
				availableMoves.addAll(makeDoubleMoves(setup, detectives, mrX, mrX.location()));
			}

			// Filter out moves to locations occupied by detectives
			availableMoves.removeIf(move -> detectives.stream().anyMatch(d -> d.location() == extractDestination(move)));

			// Filter out double moves that do not have enough tickets for both parts of the move
			availableMoves.removeIf(move -> move instanceof Move.DoubleMove && (!mrX.has(((Move.DoubleMove) move).ticket1) || !mrX.has(((Move.DoubleMove) move).ticket2)));

			// Filter out moves that require tickets Mr. X does not have
			availableMoves.removeIf(move -> move instanceof Move.SingleMove && !mrX.has(((Move.SingleMove) move).ticket));

			// Filter out secret moves if Mr. X does not have any secret tickets
			if (!mrX.has(Ticket.SECRET)) {
				availableMoves.removeIf(move -> move instanceof Move.SingleMove && ((Move.SingleMove) move).ticket == Ticket.SECRET);
				availableMoves.removeIf(move -> move instanceof Move.DoubleMove && (((Move.DoubleMove) move).ticket1 == Ticket.SECRET || ((Move.DoubleMove) move).ticket2 == Ticket.SECRET));
			}

			return availableMoves;
		}

		// Helper method to get available moves for a specific player
		private ImmutableSet<Move> getAvailableMovesFor(Player player) {
			Set<Move> moves = new HashSet<>();
			// Calculate single moves for all players
			for (ScotlandYard.Transport t : ScotlandYard.Transport.values()) {
				Ticket requiredTicket = t.requiredTicket();
				if (player.has(requiredTicket) || player.has(Ticket.SECRET)) {
					for (int destination : setup.graph.adjacentNodes(player.location())) {
						// Check if the destination is not occupied by another detective
						if (detectives.stream().noneMatch(d -> d.location() == destination)) {
							if (player.has(requiredTicket)) {
								moves.add(new Move.SingleMove(player.piece(), player.location(), requiredTicket, destination));
							}
							if (player.has(Ticket.SECRET)) {
								moves.add(new Move.SingleMove(player.piece(), player.location(), Ticket.SECRET, destination));
							}
						}
					}
				}
			}
			return ImmutableSet.copyOf(moves);
		}


		private static Set<Move.SingleMove> makeSingleMoves(GameSetup setup, List<Player> detectives, Player player, int source) {
			Set<Move.SingleMove> singleMoves = new HashSet<>();

			for (int destination : setup.graph.adjacentNodes(source)) {
				boolean locationOccupiedByDetective = false;

				for (Player detective : detectives) {
					if (detective.location() == destination) {
						locationOccupiedByDetective = true;
						break;
					}
				}

				if (locationOccupiedByDetective) {
					continue;
				}

				for (ScotlandYard.Transport t : Objects.requireNonNull(setup.graph.edgeValueOrDefault(source, destination, ImmutableSet.of()))) {
					if (player.has(t.requiredTicket())) {
						Move.SingleMove tmp1 = new Move.SingleMove(player.piece(), source, t.requiredTicket(), destination);
						singleMoves.add(tmp1);
					}
					if (player.has(ScotlandYard.Ticket.SECRET)) {
						Move.SingleMove tmp2 = new Move.SingleMove(player.piece(), source, ScotlandYard.Ticket.SECRET, destination);
						singleMoves.add(tmp2);
					}
				}
			}

			return singleMoves;
		}

		// Helper method to generate double moves for Mr. X
		private static Set<Move.DoubleMove> makeDoubleMoves(GameSetup setup, List<Player> detectives, Player player, int source) {
			Set<Move.DoubleMove> doubleMoves = new HashSet<>();

			for (int firstDestination : setup.graph.adjacentNodes(source)) {
				boolean firstLocationOccupiedByDetective = false;

				for (Player player1 : detectives) {
					if (player1.location() == firstDestination) {
						firstLocationOccupiedByDetective = true;
						break;
					}
				}

				if (firstLocationOccupiedByDetective) {
					continue;
				}

				for (ScotlandYard.Transport firstTicket : Objects.requireNonNull(setup.graph.edgeValueOrDefault(source, firstDestination, ImmutableSet.of()))) {
					if (player.has(firstTicket.requiredTicket())) {
						for (int secondDestination1 : setup.graph.adjacentNodes(firstDestination)) {
							boolean secondLocationOccupiedByDetective = false;

							for (Player player2 : detectives) {
								if (player2.location() == secondDestination1) {
									secondLocationOccupiedByDetective = true;
									break;
								}
							}

							if (secondLocationOccupiedByDetective) {
								continue;
							}

							for (ScotlandYard.Transport secondTicket : Objects.requireNonNull(setup.graph.edgeValueOrDefault(firstDestination, secondDestination1, ImmutableSet.of()))) {
								if (player.has(secondTicket.requiredTicket())) {
									if (firstTicket.requiredTicket() == secondTicket.requiredTicket()) {
										if (player.hasAtLeast(secondTicket.requiredTicket(), 2)) {
											Move.DoubleMove tmp1 = new Move.DoubleMove(player.piece(), source, firstTicket.requiredTicket(), firstDestination, secondTicket.requiredTicket(), secondDestination1);
											doubleMoves.add(tmp1);
										}
									} else {
										Move.DoubleMove tmp2 = new Move.DoubleMove(player.piece(), source, firstTicket.requiredTicket(), firstDestination, secondTicket.requiredTicket(), secondDestination1);
										doubleMoves.add(tmp2);
									}
								}

								if (player.hasAtLeast(ScotlandYard.Ticket.SECRET, 1)) {
									Move.DoubleMove tmp5 = new Move.DoubleMove(player.piece(), source, firstTicket.requiredTicket(), firstDestination, ScotlandYard.Ticket.SECRET, secondDestination1);
									doubleMoves.add(tmp5);
								}
							}
						}
					}
				}

				if (player.has(ScotlandYard.Ticket.SECRET)) {
					for (int secondDestination2 : setup.graph.adjacentNodes(firstDestination)) {
						boolean secondLocationOccupiedByDetective = false;

						for (Player player2 : detectives) {
							if (player2.location() == secondDestination2) {
								secondLocationOccupiedByDetective = true;
								break;
							}
						}

						if (secondLocationOccupiedByDetective) {
							continue;
						}

						for (ScotlandYard.Transport secondTicket2 : Objects.requireNonNull(setup.graph.edgeValueOrDefault(firstDestination, secondDestination2, ImmutableSet.of()))) {
							if (player.has(secondTicket2.requiredTicket())) {
								if (player.hasAtLeast(ScotlandYard.Ticket.SECRET, 2)) {
									Move.DoubleMove tmp3 = new Move.DoubleMove(player.piece(), source, ScotlandYard.Ticket.SECRET, firstDestination, ScotlandYard.Ticket.SECRET, secondDestination2);
									doubleMoves.add(tmp3);
								}

								if (ScotlandYard.Ticket.SECRET != secondTicket2.requiredTicket()) {
									Move.DoubleMove tmp4 = new Move.DoubleMove(player.piece(), source, ScotlandYard.Ticket.SECRET, firstDestination, secondTicket2.requiredTicket(), secondDestination2);
									doubleMoves.add(tmp4);
								}
							}
						}
					}
				}
			}

			return doubleMoves;
		}

		@Nonnull
		@Override
		public ImmutableSet<Move> getAvailableMoves() {
			Set<Move> allMoves = new HashSet<>();
			List<Piece> availablePieces = new ArrayList<>(remaining);
			Piece currentPieceMove = availablePieces.get(0);
			winner = getWinner();
			if (!winner.isEmpty()) {
				return ImmutableSet.of();
			}

			if (currentPieceMove.isMrX()) {
				if (!(!mrX.has(Ticket.DOUBLE) || !(setup.moves.size() - log.size() >= 2))) {
					allMoves.addAll(makeSingleMoves(setup, detectives, mrX, mrX.location()));
					allMoves.addAll(makeDoubleMoves(setup, detectives, mrX, mrX.location()));
				} else {
					allMoves.addAll(makeSingleMoves(setup, detectives, mrX, mrX.location()));
				}
			} else {
				availablePieces.forEach(piece -> {
					for (Player detective : detectives)
						if (detective.piece().equals(piece)) {
							allMoves.addAll(makeSingleMoves(setup, detectives, detective, detective.location()));
						}
				});
			}

			return ImmutableSet.copyOf(allMoves);
		}
		@Nonnull
		private Set<Integer> getDestinations(int location, Ticket ticket) {
			Set<Integer> destinations = new HashSet<>();
			// Get all adjacent nodes from the current location
			Set<Integer> adjacentNodes = setup.graph.adjacentNodes(location);
			for (Integer destination : adjacentNodes) {
				// Get the set of transports between the current location and the destination
				ImmutableSet<Transport> transports = setup.graph.edgeValueOrDefault(location, destination, ImmutableSet.of());
				assert transports != null;
				for (Transport transport : transports) {
					// Check if the ticket corresponds to the transport type or is a secret ticket
					if (ticket == transport.requiredTicket() || ticket == Ticket.SECRET) {
						// Add the destination if it's reachable by this transport
						destinations.add(destination);
					}
				}
			}
			return destinations;
		}

		@Nonnull
		@Override
		public GameState advance(Move move) {
			moves = getAvailableMoves();
			if(!moves.contains(move)) {
				throw new IllegalArgumentException("Illegal move: "+move);
			}

			List<Piece> pieces = new ArrayList<>();
			List<Player> detective = new ArrayList<>(detectives);
			List<LogEntry> logList = new ArrayList<>(log);

			int destination = move.accept(new Move.Visitor<Integer>() {
				@Override
				public Integer visit(Move.SingleMove move) {
					return move.destination;
				}

				@Override
				public Integer visit(Move.DoubleMove move) {
					return move.destination2;
				}
			});

			List<LogEntry> newLog = move.accept(new Move.Visitor<>() {
				final List<LogEntry> logEntries = new ArrayList<>();

				@Override
				public List<LogEntry> visit(Move.SingleMove move) {
					if (move.commencedBy().isMrX()) {
						if (setup.moves.get(log.size())) {
							logEntries.add(LogEntry.reveal(move.ticket, move.destination));
						} else logEntries.add(LogEntry.hidden(move.ticket));
					}
					return logEntries;
				}

				@Override
				public List<LogEntry> visit(Move.DoubleMove move) {
					if (move.commencedBy().isMrX()) {
						if (setup.moves.get(log.size())) {
							logEntries.add(LogEntry.reveal(move.ticket1, move.destination1));
						} else {
							logEntries.add(LogEntry.hidden(move.ticket1));
						}
						if (setup.moves.get(log.size() + 1)) {
							logEntries.add(LogEntry.reveal(move.ticket2, move.destination2));
						} else logEntries.add(LogEntry.hidden(move.ticket2));
					}
					return logEntries;
				}
			});

			if (move.commencedBy().isMrX()){
				mrX = mrX.use(move.tickets());
				mrX = mrX.at(destination);

				for (Player player : detectives) {
					if (!makeSingleMoves(setup,detectives,player,player.location()).isEmpty()){
						pieces.add(player.piece());
					}
				}
				logList.addAll(newLog);
			}

			if (move.commencedBy().isDetective()) {
				for (int i = 0; i < detectives.size(); i++) {
					Player player = detectives.get(i);
					if (player.piece().equals(move.commencedBy())) {
						detective.remove(player);
						player = player.at(destination);
						player = player.use(move.tickets());
						detective.add(player);
						mrX = mrX.give(move.tickets());
					}
				}
				pieces.addAll(remaining);
				pieces.remove(move.commencedBy());
				if (pieces.isEmpty()) {
					pieces.add(mrX.piece());
				}
			}

			return new MyGameState(setup,ImmutableSet.copyOf(pieces), ImmutableList.copyOf(logList),mrX,detective);
		}

		private void validateMove(Move move) {
			if (!getAvailableMoves().contains(move)) {
				throw new IllegalArgumentException("Illegal move: " + move);
			}
		}

		private Player updateMrXAfterMove(@Nonnull Player mrX, @Nonnull Move move) {
			// Deduct tickets from Mr. X and update his location
			return mrX.use(move.tickets()).at(extractDestination(move));
		}

		private ImmutableList<LogEntry> updateMrXLogAfterMove(ImmutableList<LogEntry> log, @Nonnull Move move) {
			// Update Mr. X's travel log based on the move
			List<LogEntry> updatedLog = new ArrayList<>(log);
			move.accept(new Move.Visitor<Void>() {
				@Override
				public Void visit(Move.SingleMove move) {
					updatedLog.add(LogEntry.hidden(move.ticket));
					return null;
				}

				@Override
				public Void visit(Move.DoubleMove move) {
					updatedLog.add(LogEntry.hidden(move.ticket1));
					updatedLog.add(LogEntry.hidden(move.ticket2));
					return null;
				}
			});
			return ImmutableList.copyOf(updatedLog);
		}

		private ImmutableSet<Piece> updateRemainingAfterMove(@Nonnull ImmutableSet<Piece> remaining, Move move) {
			// Rotate the remaining pieces to the next player
			if (remaining.isEmpty()) return remaining;
			List<Piece> pieces = new ArrayList<>(remaining);
			pieces.add(pieces.remove(0)); // Move the first piece to the end
			return ImmutableSet.copyOf(pieces);
		}

		private List<Player> updateDetectivesAfterMove(@Nonnull List<Player> detectives, Move move) {
			// Apply the move to the detective and update their location and tickets
			return detectives.stream().map(detective -> {
				if (detective.piece().equals(move.commencedBy())) {
					return detective.use(move.tickets()).at(extractDestination(move));
				}
				return detective;
			}).collect(Collectors.toList());
		}

		private Player updateMrXTicketsAfterDetectiveMove(Player mrX, Move move) {
			// Transfer the used tickets from the detective to Mr. X
			return mrX.give(move.tickets());
		}


		private int extractDestination(Move move) {
			return move.accept(new Move.Visitor<Integer>() {
				@Override
				public Integer visit(Move.SingleMove move) {
					return move.destination;
				}

				@Override
				public Integer visit(Move.DoubleMove move) {
					return move.destination2;
				}
			});
		}
	}

	@Nonnull
	public GameState build(
			GameSetup setup,
			Player mrX,
			ImmutableList<Player> detectives) {
		// Check if the graph is empty and throw an exception if it is
		if (setup.graph.nodes().isEmpty()) {
			throw new IllegalArgumentException("Graph cannot be empty");
		}

		// Constructs a new game state with the given setup, MrX, and detectives
		// Correctly initialize the remaining set to include Mr. X and detectives
		ImmutableSet<Piece> remainingPieces = Stream.concat(
				Stream.of(mrX.piece()),
				detectives.stream().map(Player::piece)
		).collect(ImmutableSet.toImmutableSet());

		return new MyGameState(setup, remainingPieces, ImmutableList.of(), mrX, detectives);
	}
}