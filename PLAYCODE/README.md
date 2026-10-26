# PLAYVERSE — Quiz Quest + Code Arena

This is an extension of the supplied PLAYCODE Spring Boot project. The UI is rebranded PLAYVERSE and includes the existing quiz flow plus a scenario-based coding arena.

## Features in this package
- Existing quiz questions, solo scoring, quiz friend challenges, leaderboard.
- Code Arena with Java/Python, topic selection, randomized scenario missions, starter code, sample tests, timer, passed-test points.
- Coding duel invitations, accept/reject, same selected problem, synchronized start timestamp, and final player scores.
- Excel question bank at `data/Coding_Missions_Question_Bank.xlsx`; runtime bank at `src/main/resources/coding-problems.json`.
- Responsive neon gaming UI.

## Run locally (Windows)
1. Install JDK 17+, Maven 3.9+, and MySQL 8+.
2. Create the database `playcode` and set `DB_USER` / `DB_PASSWORD`, or update `src/main/resources/application.properties`.
3. Open a terminal in the `PLAYCODE` folder and run `mvn spring-boot:run`.
4. Visit `http://localhost:8080`.
5. To test multiplayer, open two separate browsers/incognito windows, log in with different usernames, and keep both open. The two sessions must use the same server URL.

## Coding runner requirement
Code submissions are sent by the Spring Boot backend to the Piston execution service (`https://emkc.org/api/v2/piston/execute`) for sandboxed Java/Python execution. The app needs internet access and the external service must be reachable. If that service is down or blocks requests, coding submission will show an error; this package does not silently fake successful test results. For a public production deployment, run a private sandboxed execution service or use a vetted judge provider, add request limits, authentication, and network protections.

## Important behavior notes
- The browser cannot truly prevent Alt+Tab, OS app switching, another device, or developer tools. The UI detects tab/window focus loss and warns/submits after repeated leaving; this is not secure proctoring.
- Usernames and active matches are demo/in-memory; the original project stores quiz scores in MySQL. Restarting the app clears online state and active matches.
- The sample coding bank currently includes 14 scenario problem templates across Java and Python. A selected topic randomly chooses a problem from available items for that topic. Add more rows/problems to grow topic variety.
- In a coding duel, both players receive the same mission and start time. A duel is finalized after both players submit; winner is determined by points, then earlier submission time for equal points.

## New endpoints
- `GET /api/coding/topics?language=Java`
- `GET /api/coding/problem?language=Java&topic=If%20%2F%20Else`
- `POST /api/coding/submit`
- `POST /api/coding/challenge`
- `GET /api/coding/challenges?username=...`
- `POST /api/coding/challenge/respond`
- `POST /api/coding/match/start`
- `GET /api/coding/match/{id}?username=...`
- `POST /api/coding/match/{id}/submit`


## Local Coding Arena runner

The Coding Arena executes Java using `javac`/`java` and Python using `python3` (Linux/macOS) or `python`/`py -3` (Windows) on the same machine running Spring Boot. Install a full JDK and Python 3 and make sure the commands are available on PATH. The runner applies a short timeout and cleans up its temporary files.

**Security:** this local runner is intended only for a single-user localhost educational project and trusted code. It is not a secure sandbox for public deployment because submitted code runs with the server process's OS permissions. Do not expose this application to the internet without replacing the runner with a properly isolated execution service.

## Latest improvements
- Quiz questions are shuffled and tracked per player, language, and topic so recently served questions are avoided across attempts. When a topic's unseen pool runs low, the quiz fills remaining slots without duplicate questions within that quiz.
- Quiz duel questions are selected as a shared set and tracked for both players, keeping the duel fair.
- The home page shows the top 10 leaderboard entries. Selecting the Leaderboard heading opens a separate page with all saved results, including rank, player, language, mode, topic, score, and play time.
- The Code Duel editor keeps copy, cut, paste, and the editor context menu disabled, while manual typing remains available.

Question-history rotation is held in memory while the server is running; restarting Spring Boot clears that recent-question history. Saved leaderboard results remain in the configured database.
