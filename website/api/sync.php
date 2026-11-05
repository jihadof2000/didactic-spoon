<?php
declare(strict_types=1);
/**
 * Sanad remote follow API.
 *   register  : patient phone gets (or confirms) its follow code + secret token
 *   push      : patient phone uploads its medicine schedule and today's "taken" marks
 *   follow    : another phone enters the code -> receives the schedule
 *   pull      : follower refreshes the schedule / status
 *   unfollow  : follower leaves
 * Storage: one small JSON file per patient inside api/data (blocked from the web by .htaccess).
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

const CODE_LEN = 4;          // length of the follow code (4 digits, same as the app UI)
const MAX_FOLLOWERS = 20;
const FAIL_LIMIT = 15;       // wrong-code attempts allowed per IP ...
const FAIL_WINDOW = 600;     // ... within this many seconds
const STALE_DAYS = 180;      // patient files untouched for this long are deleted

$DATA = __DIR__ . '/data';

function out(array $a, int $http = 200): never {
    http_response_code($http);
    echo json_encode($a, JSON_UNESCAPED_UNICODE);
    exit;
}
function fail(string $err, int $http = 400): never { out(['ok' => false, 'error' => $err], $http); }
function str_in(mixed $v, int $max): string {
    if (!is_string($v)) return '';
    $chars = preg_split('//u', trim($v), $max + 1, PREG_SPLIT_NO_EMPTY);   // UTF-8 safe cut, no mbstring needed
    return $chars ? implode('', array_slice($chars, 0, $max)) : '';
}
function valid_code(string $c): bool { return (bool)preg_match('/^\d{4,8}$/', $c); }
function valid_fid(string $f): bool { return (bool)preg_match('/^[a-f0-9]{16,64}$/', $f); }
function pfile(string $code): string { global $DATA; return $DATA . '/p_' . $code . '.json'; }

/** Read-modify-write one JSON file under an exclusive lock. $fn receives the data by reference. */
function rmw(string $path, callable $fn): mixed {
    $fh = @fopen($path, 'c+');
    if (!$fh) fail('storage', 500);
    flock($fh, LOCK_EX);
    $raw = stream_get_contents($fh);
    $d = $raw ? json_decode($raw, true) : null;
    if (!is_array($d)) $d = null;
    $res = $fn($d);
    if ($d !== null) {
        ftruncate($fh, 0);
        rewind($fh);
        fwrite($fh, json_encode($d, JSON_UNESCAPED_UNICODE));
        fflush($fh);
    }
    flock($fh, LOCK_UN);
    fclose($fh);
    return $res;
}
function read_p(string $code): ?array {
    $f = pfile($code);
    if (!is_file($f)) return null;
    $d = json_decode((string)@file_get_contents($f), true);
    return is_array($d) ? $d : null;
}

/* ---- tiny per-IP throttle for wrong codes (stops guessing 4-digit codes) ---- */
function rl_path(): string { global $DATA; return $DATA . '/rl_' . md5($_SERVER['REMOTE_ADDR'] ?? 'x') . '.json'; }
function rl_recent(): array {
    $d = json_decode((string)@file_get_contents(rl_path()), true);
    $now = time();
    return array_values(array_filter(is_array($d) ? $d : [], fn($t) => is_int($t) && $t > $now - FAIL_WINDOW));
}
function rl_check(): void { if (count(rl_recent()) >= FAIL_LIMIT) fail('rate', 429); }
function rl_fail(): void { $r = rl_recent(); $r[] = time(); @file_put_contents(rl_path(), json_encode($r), LOCK_EX); }

function snapshot(array $p): array {
    return ['ok' => true, 'meds' => $p['meds'] ?? [], 'day' => $p['day'] ?? '', 'taken' => (object)($p['taken'] ?? []), 'updated' => $p['updated'] ?? 0];
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') fail('method', 405);
$raw = file_get_contents('php://input');
if ($raw === false || strlen($raw) > 200000) fail('too_big', 413);
$in = json_decode($raw, true);
if (!is_array($in)) fail('bad_json');
if (!is_dir($DATA) && !@mkdir($DATA, 0755, true)) fail('storage', 500);
if (!is_file($DATA . '/.htaccess')) @file_put_contents($DATA . '/.htaccess', "Require all denied\n");

$action = str_in($in['action'] ?? '', 20);
$code = str_in($in['code'] ?? '', 8);

/* ------------------------------------------------------------------ register */
if ($action === 'register') {
    $token = str_in($in['token'] ?? '', 64);
    if ($code !== '' && valid_code($code) && $token !== '') {
        $p = read_p($code);
        if ($p && hash_equals((string)($p['th'] ?? ''), hash('sha256', $token))) out(['ok' => true, 'code' => $code, 'token' => $token]);
    }
    // occasional cleanup of abandoned patients
    if (random_int(1, 50) === 1) {
        foreach (glob($DATA . '/p_*.json') ?: [] as $f) if (@filemtime($f) < time() - STALE_DAYS * 86400) @unlink($f);
        foreach (glob($DATA . '/rl_*.json') ?: [] as $f) if (@filemtime($f) < time() - 86400) @unlink($f);
    }
    $token = bin2hex(random_bytes(16));
    $candidates = [];
    if (valid_code($code) && strlen($code) === CODE_LEN) $candidates[] = $code;   // keep the code the user already has, if free
    for ($i = 0; $i < 60; $i++) $candidates[] = (string)random_int(10 ** (CODE_LEN - 1), 10 ** CODE_LEN - 1);
    foreach ($candidates as $c) {
        $fh = @fopen(pfile($c), 'x');          // atomic "create if free"
        if (!$fh) continue;
        fwrite($fh, json_encode(['th' => hash('sha256', $token), 'meds' => [], 'day' => '', 'taken' => new stdClass(), 'followers' => new stdClass(), 'updated' => 0, 'created' => time()]));
        fclose($fh);
        out(['ok' => true, 'code' => $c, 'token' => $token]);
    }
    fail('full', 503);
}

if (!valid_code($code)) fail('bad_code');

/* ---------------------------------------------------------------------- push */
if ($action === 'push') {
    $token = str_in($in['token'] ?? '', 64);
    $p = read_p($code);
    if (!$p || $token === '' || !hash_equals((string)($p['th'] ?? ''), hash('sha256', $token))) fail('auth', 403);

    $meds = [];
    foreach (array_slice(is_array($in['meds'] ?? null) ? $in['meds'] : [], 0, 60) as $m) {
        if (!is_array($m)) continue;
        $times = [];
        foreach (array_slice(is_array($m['times'] ?? null) ? $m['times'] : [], 0, 24) as $t)
            if (is_string($t) && preg_match('/^\d{2}:\d{2}$/', $t)) $times[] = $t;
        $id = str_in((string)($m['id'] ?? ''), 40);
        if ($id === '' || !$times) continue;
        $meds[] = [
            'id' => $id, 'name' => str_in($m['name'] ?? '', 80), 'nameEn' => str_in($m['nameEn'] ?? '', 80),
            'type' => str_in($m['type'] ?? 'pill', 12), 'times' => $times,
            'end' => max(0, (int)($m['end'] ?? 0)),
            'strength' => isset($m['strength']) && is_numeric($m['strength']) ? (float)$m['strength'] : null,
            'dose' => isset($m['dose']) && is_numeric($m['dose']) ? (float)$m['dose'] : null,
        ];
    }
    $day = str_in($in['day'] ?? '', 10);
    if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $day)) $day = '';
    $taken = [];
    foreach (array_slice(is_array($in['taken'] ?? null) ? $in['taken'] : [], 0, 200, true) as $k => $v)
        if (is_string($k) && preg_match('/^[\w\-]{1,40}\|\d{2}:\d{2}$/', $k)) $taken[$k] = 1;

    $n = rmw(pfile($code), function (&$d) use ($meds, $day, $taken) {
        if ($d === null) return 0;
        $d['meds'] = $meds; $d['day'] = $day; $d['taken'] = (object)$taken; $d['updated'] = time();
        return count((array)($d['followers'] ?? []));
    });
    out(['ok' => true, 'followers' => $n]);
}

/* ------------------------------------------------------------- follow / pull */
if ($action === 'follow' || $action === 'pull' || $action === 'unfollow') {
    rl_check();
    $fid = str_in($in['fid'] ?? '', 64);
    if (!valid_fid($fid)) fail('bad_fid');
    if (!read_p($code)) { rl_fail(); fail('not_found', 404); }

    $snap = rmw(pfile($code), function (&$d) use ($action, $fid) {
        if ($d === null) return null;
        $f = (array)($d['followers'] ?? []);
        if ($action === 'follow') {
            if (!isset($f[$fid]) && count($f) >= MAX_FOLLOWERS) return 'limit';
            $f[$fid] = time();
        } elseif ($action === 'unfollow') {
            unset($f[$fid]);
        } elseif (!isset($f[$fid])) {
            return 'not_following';
        }
        $d['followers'] = (object)$f;
        return $action === 'unfollow' ? ['ok' => true] : snapshot($d);
    });
    if ($snap === null) fail('not_found', 404);
    if ($snap === 'limit') fail('limit', 403);
    if ($snap === 'not_following') fail('not_following', 403);
    out($snap);
}

fail('unknown_action');
