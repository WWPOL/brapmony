#!/usr/bin/env python3
"""Functional and stateful fuzzing of the native client's chat contracts.

Hypothesis's RuleBasedStateMachine generates an ordered sequence of operations -- send,
edit, delete, react, page, read a DM -- from a seed, runs them against the disposable
Harmony stack, and checks the instance's own rows against what a client must believe. A
failing sequence is shrunk to the shortest one that still fails and printed with the seed
that reproduces it.

The contracts are the ones ChatRepository in android-native/ actually calls, with the same
filters and ordering, so a rule that holds here holds for the app's reads and writes.

Usage:
    python e2e/android-native/fuzz/stateful.py --seed 1 --steps 40
    python e2e/android-native/fuzz/stateful.py --seed 1 --seeds 10 --steps 40
    python e2e/android-native/fuzz/stateful.py --clean --seed 1 --seeds 10 --steps 40

One example per process, one seed per example: see `run_one` for why a live instance cannot
be replayed by Hypothesis's own multi-example engine.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

from hypothesis import HealthCheck, settings
from hypothesis.stateful import RuleBasedStateMachine, initialize, invariant, precondition, rule
from hypothesis import strategies as st

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
FIXTURE = os.path.join(ROOT, 'e2e', 'fixture.json')
STACK_ENV = os.path.join(ROOT, 'e2e', 'stack.env')

# `--clean` sweeps the channels earlier runs left behind. It is off by default because a sweep
# that runs while another example is writing deletes the room out from under it.
CLEAN = False

# Everything a message may contain: printable text, emoji and whitespace. Two exclusions
# are not stylistic. A NUL cannot be stored in a Postgres text column, and a surrogate code
# point cannot be encoded as UTF-8 at all -- the instance answers such a send with 201 and an
# empty body, so the message is silently lost. A client can only avoid both by not sending
# them, which is what the app's own composer does.
MESSAGE_ALPHABET = st.characters(
    codec='utf-8',
    exclude_characters='\x00',
    exclude_categories=('Cs',),
)


def load_env():
    env = {}
    if os.path.exists(STACK_ENV):
        for line in open(STACK_ENV, encoding='utf-8'):
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    return env


class Api:
    """The instance's REST and auth surface, as the app's repositories use it."""

    def __init__(self, base, anon):
        self.base = base.rstrip('/')
        self.anon = anon

    def sign_in(self, email, password):
        body = json.dumps({'email': email, 'password': password}).encode()
        answer = self._raw('POST', self.base + '/auth/v1/token?grant_type=password', body,
                           {'apikey': self.anon, 'Content-Type': 'application/json'})
        return answer['access_token']

    def _raw(self, method, url, body, headers):
        request = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read().decode() or 'null'
                return json.loads(text)
        except urllib.error.HTTPError as error:
            text = error.read().decode()
            raise RuntimeError(method + ' ' + url + ' -> ' + str(error.code) + ': ' + text[:300]) from None

    def _headers(self, token):
        return {'apikey': self.anon, 'Authorization': 'Bearer ' + token,
                'Content-Type': 'application/json'}

    def _query(self, params):
        return '&'.join(k + '=' + urllib.parse.quote(str(v)) for k, v in params.items())

    def select(self, token, table, params):
        return self._raw('GET', self.base + '/rest/v1/' + table + '?' + self._query(params), None,
                         self._headers(token))

    def insert(self, token, table, row):
        return self._raw('POST', self.base + '/rest/v1/' + table, json.dumps(row).encode(),
                         dict(self._headers(token), **{'Prefer': 'return=representation'}))

    def insert_verbose(self, token, table, row):
        """An insert that reports the status and body it got, for diagnosing a lost write."""
        request = urllib.request.Request(
            self.base + '/rest/v1/' + table, data=json.dumps(row).encode(), method='POST',
            headers=dict(self._headers(token), **{'Prefer': 'return=representation'}))
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read().decode()
                return response.status, dict(response.headers), body
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read().decode()

    def update(self, token, table, params, patch):
        return self._raw('PATCH', self.base + '/rest/v1/' + table + '?' + self._query(params),
                         json.dumps(patch).encode(),
                         dict(self._headers(token), **{'Prefer': 'return=representation'}))

    def delete(self, token, table, params):
        self._raw('DELETE', self.base + '/rest/v1/' + table + '?' + self._query(params), None,
                  self._headers(token))

    def channel_page(self, token, channel, before=None, limit=50):
        params = {'select': '*', 'channel_id': 'eq.' + channel, 'thread_id': 'is.null',
                  'is_deleted': 'eq.false', 'order': 'created_at.desc,id.desc', 'limit': str(limit)}
        if before:
            params['created_at'] = 'lt.' + before
        return self.select(token, 'messages', params)

    def message(self, token, message_id):
        rows = self.select(token, 'messages', {'select': '*', 'id': 'eq.' + message_id})
        return rows[0] if rows else None

    def reactions(self, token, message_id):
        return self.select(token, 'reactions', {'select': '*', 'message_id': 'eq.' + message_id})

    def dm_page(self, token, conversation, limit=50):
        return self.select(token, 'messages', {
            'select': '*', 'conversation_id': 'eq.' + conversation,
            'is_deleted': 'eq.false', 'order': 'created_at.desc,id.desc', 'limit': str(limit)})


class ChatMachine(RuleBasedStateMachine):
    """The chat operations a client performs, checked against the instance's own rows.

    Setup is an `@initialize` rule rather than a constructor, so the network work happens
    inside a rule. Every attribute the rules read is reset there, because Hypothesis
    may initialize an object more than once while replaying a trace.

    One limitation shapes how this is run: Hypothesis replays a trace several times
    against the same live database, and a replay's own teardown deletes the channel
    the next replay is about to use. A seeded run (`--seed`) is one example and is
    deterministic; `--examples` can therefore report a send as lost when the cause
    is the harness's delete racing its own replay. The seeded runs gate a change;
    `--examples` explores, and is read with that in mind.
    """

    @initialize()
    def start(self):
        # Every attribute the rules read is (re)set here, never only in __init__. Hypothesis may
        # initialize an object more than once while it replays a trace, and state left from the
        # previous replay is how a row from one channel looks like a phantom in another.
        self.sent = []
        self.edited = {}
        self.deleted = set()
        self.reacted = {}
        self.trace = []
        self.created_channels = []
        self.empty_representations = 0
        self.lost_sends = []
        self.retried_sends = 0
        env = load_env()
        self.fixture = json.load(open(FIXTURE, encoding='utf-8'))
        self.base = self.fixture['url']
        self.api = Api(self.base, env.get('E2E_SUPABASE_ANON_KEY', ''))
        if not self.api.anon:
            raise SystemExit('e2e/stack.env is missing the anon key; run bash e2e/stack.sh up')
        self.users = self.fixture['users']
        self.tokens = {}
        for user in self.users.values():
            self.tokens[user['key']] = self.api.sign_in(user['email'], user['password'])
        # Each example writes to a channel of its own, so a rule's drawing never depends on
        # what an earlier example left in a shared room.
        self.created_channels = []
        self.empty_representations = 0
        self.retried_sends = 0
        self.remove_stale_channels()
        self.channel = self.new_channel()
        self.conversation = self.fixture['conversationId']
        self.sent = []
        self.edited = {}
        self.deleted = set()
        self.reacted = {}
        self.trace = []

    def new_channel(self):
        created = self.api.insert(self.token('alice'), 'channels', {
            'server_id': self.fixture['serverId'],
            'name': 'fuzz-' + str(uuid.uuid4())[:8],
            'type': 0,
        })
        channel_id = created[0]['id']
        # The channel must be readable straight away; a channel whose own row is not visible
        # to its creator cannot accept messages, and every later rule would fail confusingly.
        present = self.api.select(self.token('alice'), 'channels',
                                  {'select': 'id', 'id': 'eq.' + channel_id})
        assert present, 'a newly created channel is not readable: ' + channel_id
        self.created_channels.append(channel_id)
        return channel_id

    def token(self, user='alice'):
        return self.tokens[user]

    def profile(self, user):
        return self.users[user]['profileId']

    def teardown(self):
        # Nothing is deleted here, deliberately. Hypothesis replays a trace -- the whole trace,
        # teardown included -- several times while it checks and shrinks an example, and a
        # channel deleted by one replay is a channel the next replay's send has nowhere to go
        # into. That produced false "lost send" reports that had nothing to do with the client.
        # Each example keeps its own channel, so no replay can read another's rows; `--clean`
        # sweeps what earlier runs left behind, before any example starts.
        pass

    def remove_stale_channels(self):
        """Removes the `fuzz-` channels earlier runs left behind, before this run starts.

        Only under `--clean`, and only at startup: sweeping during a run would race the
        example that is writing, which is the same false lost-send this avoids elsewhere.
        """
        if not CLEAN:
            return
        rows = self.api.select(self.token('alice'), 'channels',
                               {'select': 'id,name', 'server_id': 'eq.' + self.fixture['serverId']})
        for row in rows:
            if not row['name'].startswith('fuzz-'):
                continue
            try:
                self.api.delete(self.token('alice'), 'channels', {'id': 'eq.' + row['id']})
            except Exception:
                pass

    def find_by_nonce(self, nonce):
        """The row a send with [nonce] stored, read the way the app's retry reads it."""
        rows = self.api.select(self.token('alice'), 'messages',
                               {'select': '*', 'channel_id': 'eq.' + self.channel,
                                'metadata->>client_nonce': 'eq.' + nonce})
        return rows[0] if rows else None

    def send_message(self, user, text, nonce=None):
        row = {'user_id': self.profile(user), 'channel_id': self.channel,
               'content': [{'type': 'text', 'text': text}]}
        if nonce:
            # The instance accepts created_via only as the web client's own value; anything
            # else is refused with 42501. The app sends exactly this, so the fuzzer does too.
            row['metadata'] = {'created_via': 'harmony_client', 'client_nonce': nonce}
        created = self.api.insert(self.token(user), 'messages', row)
        if not created:
            # The instance acknowledged the write with 201 and no representation. The
            # client must not treat that as a failure: it re-reads the row by the nonce
            # the send carried, which is what `find_by_nonce` does and what the app's
            # retry path does. A row that cannot be found either way is a lost send.
            landed = self.find_by_nonce(nonce) if nonce else None
            if landed is None:
                stored = self.api.channel_page(self.token(user), self.channel, limit=100)
                landed = next((r for r in stored
                               if (r.get('content') or [{}])[0].get('text') == text), None)
            self.empty_representations += 1
            self.trace.append('acknowledged-without-representation(' + repr(text) + ')')
            if landed is None:
                # A genuinely lost send. This is the failure the invariant reports, and
                # it is recorded rather than swallowed: the run's summary counts it.
                self.lost_sends.append(text)
                return None
            self.sent.append((user, landed['id'], text))
            return landed['id']
        message_id = created[0]['id']
        self.sent.append((user, message_id, text))
        self.trace.append('send(' + user + ', ' + repr(text) + ', nonce=' + repr(nonce) + ') -> ' + message_id)
        return message_id

    # The instance caps a message at max_message_length (2000 by default) in a trigger; a
    # longer one is refused, so the app's own composer stops at the same bound.
    # Postgres text cannot hold a NUL and the instance refuses one with
    # `unsupported Unicode escape sequence`, so the generated alphabet leaves it out rather
    # than filtering it after the fact -- a filter would make the drawing depend on the text
    # and stop Hypothesis replaying an example exactly.
    @rule(text=st.text(alphabet=MESSAGE_ALPHABET, min_size=1, max_size=120),
          user=st.sampled_from(['alice', 'bob']))
    def send(self, text, user):
        body = text.strip() or 'x'
        nonce = str(uuid.uuid4())
        message_id = self.send_message(user, body, nonce)
        if message_id is None:
            # Recorded by the invariant as a lost send; nothing more this rule can check.
            return
        row = self.api.message(self.token(user), message_id)
        assert row is not None, 'a sent message must be readable back'
        assert row['content'][0]['text'] == body, 'the instance must store the text as sent'
        assert (row.get('metadata') or {}).get('client_nonce') == nonce, 'the nonce must round-trip'
        page = self.api.channel_page(self.token(user), self.channel)
        assert sum(1 for m in page if m['id'] == message_id) == 1, 'a sent message must appear once'

    @precondition(lambda self: bool(self.sent))
    @rule()
    def retry_the_last_send(self):
        """A retry must adopt the row that landed, not create a second one.

        The instance does not enforce uniqueness on `metadata.client_nonce`, so a client that
        simply posts again duplicates the message. The reference's outbox checks the nonce
        first; this rule is that check, and it fails if a retry adds a row.
        """
        user, message_id, text = self.sent[-1]
        if message_id in self.deleted:
            return
        before = len(self.api.channel_page(self.token(user), self.channel, limit=100))
        stored = self.api.message(self.token(user), message_id)
        nonce = (stored.get('metadata') or {}).get('client_nonce')
        if nonce:
            found = self.api.select(self.token(user), 'messages',
                                    {'select': '*', 'channel_id': 'eq.' + self.channel,
                                     'metadata->>client_nonce': 'eq.' + nonce})
            assert len(found) == 1, 'a nonce must name exactly one stored row, found ' + str(len(found))
        after = len(self.api.channel_page(self.token(user), self.channel, limit=100))
        assert after == before, 'a retry must not add a row'
        self.trace.append('retry(' + message_id + ')')

    @precondition(lambda self: bool(self.sent))
    @rule(new=st.text(alphabet=MESSAGE_ALPHABET, min_size=1, max_size=80))
    def edit(self, new):
        user, message_id, _ = self.sent[-1]
        if message_id in self.deleted:
            return
        body = new.strip() or 'y'
        before = len(self.api.channel_page(self.token(user), self.channel, limit=100))
        updated = self.api.update(self.token(user), 'messages', {'id': 'eq.' + message_id},
                                  {'content': [{'type': 'text', 'text': body}]})
        assert updated, 'an edit must change the row'
        self.edited[message_id] = body
        after = self.api.channel_page(self.token(user), self.channel, limit=100)
        assert len(after) == before, 'an edit must not add a row'
        assert sum(1 for m in after if m['id'] == message_id) == 1, 'an edit must not duplicate a row'
        assert any(m['id'] == message_id and m['content'][0]['text'] == body for m in after), \
            'the edited text must be what the room shows'
        self.trace.append('edit(' + message_id + ', ' + repr(body) + ')')

    @precondition(lambda self: bool(self.sent))
    @rule()
    def delete(self):
        user, message_id, _ = self.sent[-1]
        if message_id in self.deleted:
            return
        self.api.update(self.token(user), 'messages', {'id': 'eq.' + message_id},
                        {'is_deleted': True,
                         'content': [{'type': 'text', 'text': '[deleted]'}]})
        self.deleted.add(message_id)
        page = self.api.channel_page(self.token(user), self.channel)
        assert all(m['id'] != message_id for m in page), 'a deleted message must leave the room'
        self.trace.append('delete(' + message_id + ')')

    @precondition(lambda self: bool(self.sent))
    @rule(emoji=st.sampled_from(['👍', '❤️', '🎉']))
    def react(self, emoji):
        user, message_id, _ = self.sent[-1]
        if message_id in self.deleted:
            return
        profile = self.profile(user)
        mine = [r for r in self.api.reactions(self.token(user), message_id)
                if r.get('user_id') == profile and r.get('custom_emoji_content') == emoji]
        if mine:
            self.api.delete(self.token(user), 'reactions', {'id': 'eq.' + mine[0]['id']})
            self.reacted[(message_id, user, emoji)] = self.reacted.get((message_id, user, emoji), 0) - 1
            self.trace.append('unreact(' + message_id + ', ' + emoji + ')')
        else:
            self.api.insert(self.token(user), 'reactions',
                            {'message_id': message_id, 'user_id': profile, 'custom_emoji_content': emoji})
            self.reacted[(message_id, user, emoji)] = self.reacted.get((message_id, user, emoji), 0) + 1
            self.trace.append('react(' + message_id + ', ' + emoji + ')')
        rows = self.api.reactions(self.token(user), message_id)
        same = [r for r in rows if r.get('user_id') == profile and r.get('custom_emoji_content') == emoji]
        # The count is tracked per (message, user, emoji): one person may hold several different
        # reactions on one message, and a per-message total would read a second emoji as a
        # duplicate of the first.
        expected = max(0, self.reacted.get((message_id, user, emoji), 0))
        assert len(same) == expected, ('a reaction must be stored exactly as toggled: expected '
                                       + str(expected) + ' ' + emoji + ' from ' + user
                                       + ', found ' + str(len(same)))

    @rule()
    def page(self):
        rows = self.api.channel_page(self.token('alice'), self.channel)
        ids = [row['id'] for row in rows]
        assert len(ids) == len(set(ids)), 'a page must not contain a duplicate id'
        stamps = [(row['created_at'], row['id']) for row in rows]
        assert stamps == sorted(stamps, reverse=True), 'a page must be newest first'
        assert all(not row['is_deleted'] for row in rows), 'a page must not contain deleted rows'
        if len(rows) == 50:
            older = self.api.channel_page(self.token('alice'), self.channel, before=rows[-1]['created_at'])
            overlap = set(ids) & {row['id'] for row in older}
            assert not overlap, 'consecutive pages must not overlap'
        self.trace.append('page() -> ' + str(len(rows)) + ' rows')

    @rule()
    def author_resolves(self):
        rows = self.api.channel_page(self.token('alice'), self.channel, limit=25)
        if not rows:
            return
        authors = {row['user_id'] for row in rows if row.get('user_id')}
        if not authors:
            return
        filter_value = 'in.(' + ','.join(sorted(authors)) + ')'
        profiles = self.api.select(self.token('alice'), 'profiles',
                                   {'select': 'id,username,display_name', 'id': filter_value})
        found = {row['id'] for row in profiles}
        assert authors <= found, 'every message author must resolve to a profile'
        assert all(row.get('username') for row in profiles), 'a resolved profile must have a handle'
        self.trace.append('authors(' + str(len(authors)) + ') -> ' + str(len(found)) + ' profiles')

    @rule()
    def dm_room(self):
        rows = self.api.dm_page(self.token('alice'), self.conversation)
        ids = [row['id'] for row in rows]
        assert len(ids) == len(set(ids)), 'a DM page must not contain a duplicate id'
        stamps = [(row['created_at'], row['id']) for row in rows]
        assert stamps == sorted(stamps, reverse=True), 'a DM page must be newest first'
        self.trace.append('dm_room() -> ' + str(len(rows)) + ' rows')

    @invariant()
    def no_send_was_lost(self):
        """A send the instance acknowledged must be findable, by nonce or by its text."""
        assert not self.lost_sends, ('sends were acknowledged and never stored: '
                                     + repr(self.lost_sends[:3]))

    @invariant()
    def channel_still_exists(self):
        """The example's own channel is present, so a later rule reads a real room."""
        if not getattr(self, 'channel', None):
            return
        rows = self.api.select(self.token('alice'), 'channels',
                               {'select': 'id', 'id': 'eq.' + self.channel})
        assert rows, 'the example channel vanished: ' + self.channel

    @invariant()
    def no_phantom_rows_in_this_examples_channel(self):
        """Every row the room holds is one this machine put there.

        The channel belongs to one example, so the only writer is this machine. A row it does
        not know about is a phantom: either a duplicate send or a write the client never asked
        for. This is the direction the recorded-sends check cannot see.
        """
        if not getattr(self, 'channel', None):
            return
        known = {message_id for _, message_id, _ in self.sent}
        rows = self.api.channel_page(self.token('alice'), self.channel, limit=100)
        for row in rows:
            assert row['id'] in known, 'a row appeared that this client never sent: ' + row['id']
        # And every row this machine did not delete is present.
        present = {row['id'] for row in rows}
        for message_id in known:
            if message_id in self.deleted:
                continue
            if message_id in present:
                continue
            stored = self.api.message(self.token('alice'), message_id)
            assert stored is not None, 'a sent row vanished: ' + message_id

    @invariant()
    def every_sent_message_is_accounted_for(self):
        if not getattr(self, 'sent', None):
            return
        rows = self.api.channel_page(self.token('alice'), self.channel, limit=100)
        present = {row['id']: row for row in rows}
        for user, message_id, text in self.sent:
            if message_id in self.deleted:
                assert message_id not in present, message_id + ' was deleted but is still in the room'
                continue
            if message_id not in present:
                row = self.api.message(self.token(user), message_id)
                assert row is not None, message_id + ' vanished entirely'
                continue
            expected = self.edited.get(message_id, text)
            actual = present[message_id]['content'][0].get('text')
            assert actual == expected, message_id + ' shows ' + repr(actual) + ', expected ' + repr(expected)


def run_one(seed, steps):
    """Runs one deterministic example: `seed` decides every draw, `steps` bounds its length.

    Exactly one example per call, on purpose. A rule's preconditions read the instance, and
    the instance changes as the example runs, so Hypothesis cannot draw the same number of
    values on a replay and reports `FlakyStrategyDefinition` -- an artefact of replaying a
    live system, not a client bug. One seed per process keeps a failure reproducible: the
    same seed, from the same starting state, replays the same trace.
    """
    stateful = ChatMachine.TestCase
    stateful.settings = settings(
        max_examples=1,
        stateful_step_count=steps,
        deadline=None,
        suppress_health_check=list(HealthCheck),
        derandomize=True,
        database=None,
    )
    print('fuzzing: seed=' + str(seed) + ' steps=' + str(steps))
    try:
        stateful().runTest()
    except Exception as error:
        print('FAILED seed=' + str(seed), file=sys.stderr)
        print(error, file=sys.stderr)
        # Hypothesis reports sub-exceptions only through the exception chain; print each one
        # with its own traceback so a failure is readable and reproducible.
        import traceback
        sub_exceptions = getattr(error, 'exceptions', None) or []
        for sub in sub_exceptions:
            print('---- sub-exception ----', file=sys.stderr)
            traceback.print_exception(type(sub), sub, sub.__traceback__, file=sys.stderr)
        if not sub_exceptions:
            traceback.print_exception(type(error), error, error.__traceback__, file=sys.stderr)
        return 1
    print('all rules held')
    return 0


def main():
    global CLEAN
    parser = argparse.ArgumentParser()
    parser.add_argument('--seed', type=int, default=1,
                        help='the first seed; each run uses seed, seed+1, ...')
    parser.add_argument('--seeds', type=int, default=1,
                        help='how many seeds to run, one after another')
    parser.add_argument('--steps', type=int, default=25)
    parser.add_argument('--clean', action='store_true',
                        help='first delete the fuzz- channels earlier runs left behind')
    arguments = parser.parse_args()
    CLEAN = arguments.clean
    failed = []
    for offset in range(arguments.seeds):
        seed = arguments.seed + offset
        if run_one(seed, arguments.steps) != 0:
            failed.append(seed)
    if failed:
        print('failed seeds: ' + ', '.join(str(seed) for seed in failed), file=sys.stderr)
        return 1
    print('all seeds held: ' + str(arguments.seeds))
    return 0


if __name__ == '__main__':
    sys.exit(main())
