-- Talk player helpers: speed steps, paused clip switching, frame-to-Preview.
local utils = require 'mp.utils'

local STEP, MIN, MAX = 0.5, 0.5, 16

local function set_speed(s)
    s = math.max(MIN, math.min(MAX, s))
    mp.set_property_number("speed", s)
    mp.osd_message(string.format("%gx", s), 1)
end

local function speed() return mp.get_property_number("speed", 1) end

mp.add_key_binding(nil, "speed-up",    function() set_speed(speed() + STEP) end)
mp.add_key_binding(nil, "speed-down",  function() set_speed(speed() - STEP) end)
mp.add_key_binding(nil, "speed-reset", function() set_speed(1) end)

-- pause and speed persist across files, so setting them first makes the next clip load paused at 1x.
local function switch(cmd)
    mp.set_property_bool("pause", true)
    mp.set_property_number("speed", 1)
    mp.command(cmd)
end

mp.add_key_binding(nil, "next", function() switch("playlist-next") end)
mp.add_key_binding(nil, "prev", function() switch("playlist-prev") end)

mp.add_key_binding(nil, "live-text", function()
    mp.set_property_bool("pause", true)
    local dir = utils.join_path(mp.get_property("working-directory"), ".screenshots")
    os.execute(string.format("mkdir -p %q", dir))
    local path = utils.join_path(dir, os.date("frame-%Y%m%d-%H%M%S") .. ".png")
    mp.commandv("screenshot-to-file", path, "video")
    mp.commandv("run", "open", "-a", "Preview", path)
end)
