#ifndef SPLASHSCREEN_H
#define SPLASHSCREEN_H

#include <QFont>
#include <QPointF>
#include <QVector>
#include <QWidget>

class SplashScreen : public QWidget {
    Q_OBJECT
    Q_PROPERTY(qreal progress READ progress WRITE setProgress)

public:
    explicit SplashScreen(QWidget *parent = nullptr);

    qreal progress() const;
    void setProgress(qreal value);

signals:
    void finished();

protected:
    void paintEvent(QPaintEvent *event) override;

private:
    QFont calligraphyFont(int pointSize) const;
    QVector<QPointF> particles;
    qreal m_progress = 0.0;
};

#endif
