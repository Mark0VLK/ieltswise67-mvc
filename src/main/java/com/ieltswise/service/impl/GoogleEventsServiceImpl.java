package com.ieltswise.service.impl;

import com.ieltswise.entity.Event;
import com.ieltswise.entity.FreeAndBusyHoursOfTheDay;
import com.ieltswise.service.GoogleEventsService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static java.time.YearMonth.of;
import static java.time.ZoneId.systemDefault;
import static java.time.ZonedDateTime.parse;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.Locale.ROOT;
import static org.apache.http.protocol.HTTP.USER_AGENT;


@Service
public class GoogleEventsServiceImpl implements GoogleEventsService {
    private static final String JSON_START = "start";
    private static final String JSON_END = "end";
    private static final String JSON_STATUS = "status";
    private static final String JSON_DATETIME = "dateTime";
    private static final String JSON_DATE = "date";
    private static final String STATUS_CANCELED = "cancelled";

    @Value("${google.credentials.key}")
    private String googleCredentialKey;

    @Autowired
    public GoogleEventsServiceImpl() {
    }


    @Override
    public List<Event> getEvents(String tutorID) {
        try {
            URL obj = new URL("https://www.googleapis.com/calendar/v3/calendars/" + tutorID
                    + "/events?key=" + googleCredentialKey);
            return extractEvents(createJSONObjectResponse(obj).getJSONArray("items"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Event> extractEvents(JSONArray eventItems) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < eventItems.length(); i++) {
            JSONObject eventItem = eventItems.getJSONObject(i);
            if (!eventItem.getString(JSON_STATUS).equals(STATUS_CANCELED)) {
                JSONObject start = eventItem.getJSONObject(JSON_START);
                JSONObject end = eventItem.getJSONObject(JSON_END);

                Event event = new Event();
                event.setStartDate(extractDate(start));
                event.setEndDate(extractDate(end));
                event.setStatus(eventItem.getString(JSON_STATUS));

                events.add(event);
            }
        }
        return events;
    }

    private ZonedDateTime extractDate(JSONObject dateTime) {
        if (dateTime.toMap().get(JSON_DATETIME) != null) {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .append(ISO_DATE_TIME)
                    .toFormatter(ROOT);
            return parse(dateTime.getString(JSON_DATETIME), formatter);
        } else if (dateTime.toMap().get(JSON_DATE) != null) {
            LocalDate localDate = LocalDate.parse(dateTime.getString(JSON_DATE));
            return localDate.atStartOfDay(systemDefault());
        }
        return null;
    }

    @Override
    public List<FreeAndBusyHoursOfTheDay> getEventsByYearAndMonth(String tutorId, int year, int month) {
        try {
            ZonedDateTime startOfMonth = of(year, month).atDay(1).atStartOfDay(systemDefault());
            ZonedDateTime endOfMonth = of(year, month).atEndOfMonth().atStartOfDay(systemDefault());

            URL obj = new URL(createUrl(tutorId, startOfMonth, endOfMonth));

            return findAllEventsByYearAndMonth(createJSONObjectResponse(obj).getJSONArray("items"), startOfMonth,
                    endOfMonth);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JSONObject createJSONObjectResponse(URL obj) throws IOException {
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", USER_AGENT);
        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        return new JSONObject(response.toString());
    }

    private String createUrl(String tutorId, ZonedDateTime startOfMonth, ZonedDateTime endOfMonth) {
        String formattedTimeMin = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startOfMonth);
        String formattedTimeMax = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(endOfMonth);
        String encodedTimeMin = URLEncoder.encode(formattedTimeMin, StandardCharsets.UTF_8);
        String encodedTimeMax = URLEncoder.encode(formattedTimeMax, StandardCharsets.UTF_8);
        String apiUrl = "https://www.googleapis.com/calendar/v3/calendars/" + tutorId + "/events";
        return apiUrl + "?timeMin=" + encodedTimeMin + "&timeMax=" + encodedTimeMax + "&key=" + googleCredentialKey;
    }

    private List<FreeAndBusyHoursOfTheDay> findAllEventsByYearAndMonth(JSONArray jsonArray, ZonedDateTime startOfMonth,
                                                                       ZonedDateTime endOfMonth) {

        TreeMap<Long, TreeMap<Long, Boolean>> dateClockStatus = new TreeMap<>();
        TreeMap<Long, Boolean> hourStatus;

        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject eventItem = jsonArray.getJSONObject(i);
            if (!eventItem.getString(JSON_STATUS).equals(STATUS_CANCELED)) {

                JSONObject start = eventItem.getJSONObject(JSON_START);
                JSONObject end = eventItem.getJSONObject(JSON_END);

                ZonedDateTime eventStartDate = extractDate(start);
                ZonedDateTime eventEndDate = extractDate(end);

                TreeMap<Long, TreeMap<Long, Boolean>> temporarily = new TreeMap<>();

                Instant instant = Objects.requireNonNull(eventStartDate).with(LocalTime.MIDNIGHT).toInstant();
                long day = instant.toEpochMilli();

                hourStatus = new TreeMap<>();

                ZonedDateTime check = eventStartDate.toLocalDate().atStartOfDay().atZone(eventStartDate.getZone()).plusDays(1).minusSeconds(1);

                while (eventStartDate.isBefore(eventEndDate)) {

                    if (eventStartDate.isAfter(check)) {
                        temporarily.put(day, hourStatus);
                        instant = eventStartDate.with(LocalTime.MIDNIGHT).toInstant();
                        day = instant.toEpochMilli();
                        hourStatus = new TreeMap<>();


                        check = eventStartDate.toLocalDate().atStartOfDay().atZone(eventStartDate.getZone()).plusDays(1).minusSeconds(1);
                    }

                    ZonedDateTime utcDateTime = eventStartDate.withMinute(0).withZoneSameInstant(java.time.ZoneOffset.UTC);
                    Long timestamp = utcDateTime.toEpochSecond() * 1000;


                    hourStatus.put(timestamp, true);


                    if (eventStartDate.withMinute(0).equals(Objects.requireNonNull(eventEndDate).withMinute(0)))
                        break;

                    eventStartDate = eventStartDate.withMinute(0).plusHours(1);
                }

                temporarily.put(day, hourStatus);

                for (Map.Entry<Long, TreeMap<Long, Boolean>> entry : temporarily.entrySet()) {
                    Long outerKey = entry.getKey();
                    TreeMap<Long, Boolean> innerMap = entry.getValue();

                    if (dateClockStatus.containsKey(outerKey)) {
                        TreeMap<Long, Boolean> existingValuesTime = dateClockStatus.get(outerKey);
                        existingValuesTime.putAll(innerMap);
                    } else {
                        dateClockStatus.put(outerKey, innerMap);
                    }

                }
            }
        }


        for (ZonedDateTime dateOne = startOfMonth.with(LocalTime.MIDNIGHT); !dateOne.isAfter(endOfMonth); dateOne = dateOne.plusDays(1)) {

            Instant instant = dateOne.toInstant();
            Long dateToCheck = instant.toEpochMilli();

            if (dateClockStatus.containsKey(dateToCheck)) {
                TreeMap<Long, Boolean> existingValuesTime = dateClockStatus.get(dateToCheck);
                for (int i = 0; i < 24; i++) {

                    ZonedDateTime currentHour = dateOne.withHour(i);
                    ZonedDateTime utcDateTime = currentHour.withZoneSameInstant(java.time.ZoneOffset.UTC);
                    Long timestamp = utcDateTime.toEpochSecond() * 1000;
                    if (!existingValuesTime.containsKey(timestamp)) {
                        existingValuesTime.put(timestamp, false);
                    }
                }
            } else {
                hourStatus = new TreeMap<>();
                for (int i = 0; i < 24; i++) {

                    ZonedDateTime currentHour = dateOne.withHour(i);
                    ZonedDateTime utcDateTime = currentHour.withZoneSameInstant(java.time.ZoneOffset.UTC);
                    Long timestamp = utcDateTime.toEpochSecond() * 1000;
                    hourStatus.put(timestamp, false);
                }
                dateClockStatus.put(dateToCheck, hourStatus);
            }
        }
        return getAllHoursAndTheirStatusForAllDaysOfTheMonth(dateClockStatus);
    }


    private List<FreeAndBusyHoursOfTheDay> getAllHoursAndTheirStatusForAllDaysOfTheMonth(TreeMap<Long, TreeMap<Long,
            Boolean>> dateClockStatus) {

        List<FreeAndBusyHoursOfTheDay> eventsOfMonth = new ArrayList<>();

        FreeAndBusyHoursOfTheDay eventsOfDay;

        List<Map<String, Object>> informationAboutAllHoursOfTheDay;

        Map<String, Object> hourStatus;

        for (Map.Entry<Long, TreeMap<Long, Boolean>> entry1 : dateClockStatus.entrySet()) {
            Long dateKey = entry1.getKey();
            TreeMap<Long, Boolean> hoursAndTheirStatus = entry1.getValue();
            informationAboutAllHoursOfTheDay = new ArrayList<>();
            for (Map.Entry<Long, Boolean> entry2 : hoursAndTheirStatus.entrySet()) {
                Long hourKey = entry2.getKey();
                Boolean status = entry2.getValue();
                hourStatus = new HashMap<>();
                hourStatus.put("time", hourKey);
                hourStatus.put("occupied", status);
                informationAboutAllHoursOfTheDay.add(hourStatus);
            }
            eventsOfDay = new FreeAndBusyHoursOfTheDay();
            eventsOfDay.setDate(dateKey);
            eventsOfDay.setTime(informationAboutAllHoursOfTheDay);
            eventsOfMonth.add(eventsOfDay);
        }
        return eventsOfMonth;
    }
}
