Relay Speedrun
==============
This is a pure server-side mod that provides a game play of a multi-player relay speedrun, inspired by
https://www.youtube.com/watch?v=WU5etmP0h0Y.

In a relay speedrun, players take turns controlling a shared speedrunner(a virtual player entity controlled by different
players each turn, referred to as the "**speedrunner**") to carry out a speedrun. In each turn, the current player can
take control over the speedrunner for a certain amount of time. At the end of each turn, the next player would take over
and inherit health, hunger, inventory, and most other player data crucial to a speedrun, from the previous player. This
player would then continue the speedrun for their turn. Players who are not controlling the speedrunner would be in
spectator mode and not be able to know any information about the current progress of the speedrun. They can neither
move and inspect the world nor spectate the current player. The order of turns is determined by the order of players
joining the server. The ordering would cycle.

For the current player, an actionbar title would be used to show the time left for this turn. For spectators, the title
shows the amount of time until it is the turn for this spectator; the subtitle shows information about the current
player and how much time they have left; the actionbar title shows information about the next player. There would also
be a sidebar for spectators showing the RTA and IGT of the current speedrun.

A relay speedrun is controlled by the command **/relay**. A speedrun can be started by **/relay start**, paused by
**/relay pause**, resumed by **/relay resume**, and stopped by **/relay stop**. The time limit of a single turn can be
queried and set with **/relay countdown get** and **/relay countdown set**.